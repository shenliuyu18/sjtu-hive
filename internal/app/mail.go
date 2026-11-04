package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"mime"
	"net"
	"net/mail"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/emersion/go-imap"
	imapclient "github.com/emersion/go-imap/client"
	"github.com/emersion/go-message/charset"
)

type MailAddress struct {
	Name    *string `json:"name"`
	Address *string `json:"address"`
}

type MailAttachmentInfo struct {
	Filename    string `json:"filename"`
	Size        int64  `json:"size"`
	Part        string `json:"part"`
	ContentID   string `json:"contentId,omitempty"`
	Inline      bool   `json:"inline"`
	ContentType string `json:"contentType,omitempty"`
	URL         string `json:"url,omitempty"`
	LocalPath   string `json:"-"`
}

type MailMessageSummary struct {
	ID             int64        `json:"id"`
	UID            uint32       `json:"uid"`
	Subject        *string      `json:"subject"`
	Sender         *MailAddress `json:"sender"`
	Date           *string      `json:"date"`
	Seen           bool         `json:"seen"`
	Flagged        bool         `json:"flagged"`
	HasAttachments bool         `json:"hasAttachments"`
	Snippet        *string      `json:"snippet"`
	Size           *int64       `json:"size"`
}

type MailMessageDetail struct {
	MailMessageSummary
	FolderID    int64                `json:"folderId"`
	To          []MailAddress        `json:"to"`
	Cc          []MailAddress        `json:"cc"`
	BodyText    *string              `json:"bodyText"`
	BodyHTML    *string              `json:"bodyHtml"`
	Attachments []MailAttachmentInfo `json:"attachments"`
}

type MailFolder struct {
	ID           int64   `json:"id"`
	Path         string  `json:"path"`
	Name         string  `json:"name"`
	SpecialUse   *string `json:"specialUse"`
	Total        *int64  `json:"total"`
	Unread       *int64  `json:"unread"`
	LastSyncedAt *string `json:"lastSyncedAt"`
}

type MailAccountInfo struct {
	Address      string  `json:"address"`
	LastSyncedAt *string `json:"lastSyncedAt"`
}

type MailSyncStatus struct {
	Phase      string  `json:"phase"`
	FolderPath *string `json:"folderPath"`
	Processed  int     `json:"processed"`
	Total      *int    `json:"total"`
	Error      *string `json:"error"`
	StartedAt  *string `json:"startedAt"`
	FinishedAt *string `json:"finishedAt"`
}

type MailState struct {
	Account *MailAccountInfo `json:"account"`
	Sync    MailSyncStatus   `json:"sync"`
}

type MailResult struct {
	OK      bool   `json:"ok"`
	Warning string `json:"warning,omitempty"`
	Error   string `json:"error,omitempty"`
}

type MailDraft struct {
	ID          string   `json:"id"`
	To          []string `json:"to"`
	Cc          []string `json:"cc"`
	Bcc         []string `json:"bcc"`
	Subject     string   `json:"subject"`
	Text        string   `json:"text"`
	HTML        string   `json:"html"`
	Attachments []string `json:"attachments"`
	UpdatedAt   string   `json:"updatedAt"`
}

type MailDraftSummary struct {
	ID          string   `json:"id"`
	To          []string `json:"to"`
	Subject     string   `json:"subject"`
	UpdatedAt   string   `json:"updatedAt"`
	Attachments int      `json:"attachments"`
}

type MailTrashItem struct {
	MailMessageSummary
	FolderName string `json:"folderName"`
	DeletedAt  string `json:"deletedAt"`
}

type MailCredential struct {
	Address  string `json:"address"`
	Password string `json:"password"`
}

type mailAccount struct {
	ID          int64
	Address     string
	DisplayName string
	IMAPHost    string
	IMAPPort    int
	SMTPHost    string
	SMTPPort    int
	CreatedAt   string
}

type Mail struct {
	db      *sql.DB
	paths   Paths
	secrets *SecretStore
	media   *MediaServer
	emit    func(string, any)

	imapMu sync.Mutex
	client *imapclient.Client

	statusMu sync.Mutex
	status   MailSyncStatus
}

func NewMail(db *sql.DB, paths Paths, secrets *SecretStore, media *MediaServer, emit func(string, any)) *Mail {
	return &Mail{
		db: db, paths: paths, secrets: secrets, media: media, emit: emit,
		status: MailSyncStatus{Phase: "idle"},
	}
}

func (m *Mail) statusCopy() MailSyncStatus {
	m.statusMu.Lock()
	defer m.statusMu.Unlock()
	return m.status
}

func (m *Mail) setStatus(status MailSyncStatus) {
	m.statusMu.Lock()
	m.status = status
	copy := status
	m.statusMu.Unlock()
	if m.emit != nil {
		m.emit("mail.sync", copy)
	}
}

func (m *Mail) account() (*mailAccount, error) {
	var account mailAccount
	var displayName sql.NullString
	err := m.db.QueryRow(`SELECT id, address, display_name, imap_host, imap_port, smtp_host, smtp_port, created_at
		FROM mail_accounts WHERE id = 1`).
		Scan(&account.ID, &account.Address, &displayName, &account.IMAPHost, &account.IMAPPort, &account.SMTPHost, &account.SMTPPort, &account.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	account.DisplayName = displayName.String
	return &account, nil
}

func (m *Mail) credentials() (*MailCredential, error) {
	var credential MailCredential
	ok, err := m.secrets.Load("mail-credentials", &credential)
	if err != nil {
		return nil, err
	}
	if !ok || credential.Address == "" || credential.Password == "" {
		return nil, errors.New("邮箱凭据不存在，请重新配置")
	}
	return &credential, nil
}

func (m *Mail) State() (MailState, error) {
	account, err := m.account()
	if err != nil {
		return MailState{}, err
	}
	state := MailState{Sync: m.statusCopy()}
	if account == nil {
		if state.Sync.Phase == "" {
			state.Sync.Phase = "idle"
		}
		return state, nil
	}
	var lastSynced sql.NullString
	_ = m.db.QueryRow(`SELECT MAX(last_synced_at) FROM mail_folders`).Scan(&lastSynced)
	info := &MailAccountInfo{Address: account.Address}
	if lastSynced.Valid {
		value := lastSynced.String
		info.LastSyncedAt = &value
	}
	state.Account = info
	return state, nil
}

func (m *Mail) Configure(input map[string]any) (MailState, error) {
	address := strings.TrimSpace(inputText(input, "address"))
	password := inputText(input, "password")
	imapHost := firstNonEmpty(inputText(input, "imapHost"), "mail.sjtu.edu.cn")
	imapPort := intFromAny(input["imapPort"], 993)
	smtpHost := firstNonEmpty(inputText(input, "smtpHost"), "mail.sjtu.edu.cn")
	smtpPort := intFromAny(input["smtpPort"], 465)
	if _, err := netmailParseAddress(address); err != nil {
		return MailState{}, errors.New("邮箱地址格式无效")
	}
	if password == "" {
		return MailState{}, errors.New("请填写邮箱密码或授权码")
	}
	if imapPort < 1 || imapPort > 65535 || smtpPort < 1 || smtpPort > 65535 {
		return MailState{}, errors.New("邮箱端口无效")
	}
	account := &mailAccount{
		Address: address, IMAPHost: imapHost, IMAPPort: imapPort,
		SMTPHost: smtpHost, SMTPPort: smtpPort, CreatedAt: Now(),
	}
	if err := m.testIMAP(account, password); err != nil {
		return MailState{}, fmt.Errorf("连接 IMAP 失败：%w", err)
	}
	previous, _ := m.account()
	if previous != nil && !strings.EqualFold(previous.Address, address) {
		_, _ = m.db.Exec(`DELETE FROM mail_messages`)
		_, _ = m.db.Exec(`DELETE FROM mail_folders`)
	}
	m.imapMu.Lock()
	if m.client != nil {
		_ = m.client.Logout()
		m.client = nil
	}
	m.imapMu.Unlock()
	_, err := m.db.Exec(`INSERT INTO mail_accounts (id, address, display_name, imap_host, imap_port, smtp_host, smtp_port, created_at)
		VALUES (1, ?, '', ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET address = excluded.address, imap_host = excluded.imap_host,
		  imap_port = excluded.imap_port, smtp_host = excluded.smtp_host, smtp_port = excluded.smtp_port`,
		address, imapHost, imapPort, smtpHost, smtpPort, Now())
	if err != nil {
		return MailState{}, err
	}
	if err := m.secrets.Save("mail-credentials", MailCredential{Address: address, Password: password}); err != nil {
		return MailState{}, err
	}
	state, _ := m.State()
	if m.emit != nil {
		m.emit("mail.state", state)
	}
	go func() {
		_, _ = m.Sync()
	}()
	return state, nil
}

func netmailParseAddress(value string) (string, error) {
	parsed, err := mail.ParseAddress(value)
	if err != nil {
		return "", err
	}
	return parsed.Address, nil
}

func intFromAny(value any, fallback int) int {
	switch number := value.(type) {
	case float64:
		return int(number)
	case int:
		return number
	case string:
		parsed, err := strconv.Atoi(strings.TrimSpace(number))
		if err == nil {
			return parsed
		}
	}
	return fallback
}

func (m *Mail) testIMAP(account *mailAccount, password string) error {
	addr := net.JoinHostPort(account.IMAPHost, strconv.Itoa(account.IMAPPort))
	client, err := imapclient.DialTLS(addr, nil)
	if err != nil {
		return err
	}
	defer client.Logout()
	client.Timeout = 20 * time.Second
	if err := client.Login(account.Address, password); err != nil {
		return err
	}
	ch := make(chan *imap.MailboxInfo, 16)
	done := make(chan error, 1)
	go func() { done <- client.List("", "*", ch) }()
	for range ch {
	}
	return <-done
}

func (m *Mail) Disconnect() (MailState, error) {
	m.imapMu.Lock()
	if m.client != nil {
		_ = m.client.Logout()
		m.client = nil
	}
	m.imapMu.Unlock()
	_ = m.secrets.Delete("mail-credentials")
	_, _ = m.db.Exec(`DELETE FROM mail_messages`)
	_, _ = m.db.Exec(`DELETE FROM mail_folders`)
	_, _ = m.db.Exec(`DELETE FROM mail_drafts`)
	_, _ = m.db.Exec(`DELETE FROM mail_accounts`)
	m.setStatus(MailSyncStatus{Phase: "idle"})
	state, _ := m.State()
	if m.emit != nil {
		m.emit("mail.state", state)
	}
	return state, nil
}

func (m *Mail) connectLocked() error {
	if m.client != nil {
		if err := m.client.Noop(); err == nil {
			return nil
		}
		_ = m.client.Logout()
		m.client = nil
	}
	account, err := m.account()
	if err != nil {
		return err
	}
	if account == nil {
		return errors.New("尚未配置邮箱")
	}
	credential, err := m.credentials()
	if err != nil {
		return err
	}
	addr := net.JoinHostPort(account.IMAPHost, strconv.Itoa(account.IMAPPort))
	client, err := imapclient.DialTLS(addr, nil)
	if err != nil {
		return fmt.Errorf("无法连接邮箱服务器：%w", err)
	}
	client.Timeout = 45 * time.Second
	if err := client.Login(credential.Address, credential.Password); err != nil {
		_ = client.Logout()
		return fmt.Errorf("邮箱登录失败：%w", err)
	}
	m.client = client
	return nil
}

func (m *Mail) closeClientLocked() {
	if m.client != nil {
		_ = m.client.Logout()
		m.client = nil
	}
}

func (m *Mail) Sync() (MailState, error) {
	m.imapMu.Lock()
	defer m.imapMu.Unlock()
	now := Now()
	m.setStatus(MailSyncStatus{Phase: "connecting", StartedAt: &now})
	if err := m.connectLocked(); err != nil {
		m.closeClientLocked()
		message := err.Error()
		m.setStatus(MailSyncStatus{Phase: "error", Error: &message, StartedAt: &now, FinishedAt: &now})
		return m.State()
	}
	if err := m.syncLocked(); err != nil {
		m.closeClientLocked()
		message := err.Error()
		m.setStatus(MailSyncStatus{Phase: "error", Error: &message, StartedAt: &now, FinishedAt: &now})
		state, _ := m.State()
		return state, err
	}
	finished := Now()
	m.setStatus(MailSyncStatus{Phase: "idle", StartedAt: &now, FinishedAt: &finished})
	state, _ := m.State()
	if m.emit != nil {
		m.emit("mail.state", state)
	}
	return state, nil
}

type mailboxEntry struct {
	info  *imap.MailboxInfo
	attrs []string
}

func (m *Mail) syncLocked() error {
	ch := make(chan *imap.MailboxInfo, 32)
	done := make(chan error, 1)
	go func() { done <- m.client.List("", "*", ch) }()
	mailboxes := []*imap.MailboxInfo{}
	for info := range ch {
		mailboxes = append(mailboxes, info)
	}
	if err := <-done; err != nil {
		return fmt.Errorf("无法读取邮箱文件夹：%w", err)
	}
	sort.Slice(mailboxes, func(i, j int) bool {
		return strings.EqualFold(mailboxes[i].Name, "INBOX") && !strings.EqualFold(mailboxes[j].Name, "INBOX")
	})
	total := 0
	for _, info := range mailboxes {
		if hasAttr(info.Attributes, imap.NoSelectAttr) {
			continue
		}
		total++
	}
	m.setStatus(MailSyncStatus{Phase: "syncing", Total: &total})
	processed := 0
	for _, info := range mailboxes {
		if hasAttr(info.Attributes, imap.NoSelectAttr) {
			continue
		}
		name := info.Name
		folderPath := &name
		status := m.statusCopy()
		status.Phase = "syncing"
		status.FolderPath = folderPath
		status.Processed = processed
		status.Total = &total
		m.setStatus(status)
		mailbox, err := m.client.Select(info.Name, false)
		if err != nil {
			processed++
			continue
		}
		folderID, err := m.upsertFolder(info, mailbox, m.countUnseen(mailbox))
		if err != nil {
			return err
		}
		if err := m.syncFolderMessages(folderID, info, mailbox, &processed); err != nil {
			processed++
			continue
		}
		processed++
	}
	return nil
}

func hasAttr(attrs []string, target string) bool {
	for _, attr := range attrs {
		if strings.EqualFold(attr, target) {
			return true
		}
	}
	return false
}

func specialUse(attrs []string) string {
	for _, attr := range attrs {
		switch strings.ToLower(attr) {
		case "\\sent":
			return "sent"
		case "\\drafts":
			return "drafts"
		case "\\trash":
			return "trash"
		case "\\junk":
			return "junk"
		case "\\archive":
			return "archive"
		case "\\inbox":
			return "inbox"
		}
	}
	return ""
}

func folderDisplayName(info *imap.MailboxInfo) string {
	name := info.Name
	if info.Delimiter != "" {
		parts := strings.Split(name, info.Delimiter)
		if len(parts) > 0 {
			return parts[len(parts)-1]
		}
	}
	return name
}

func (m *Mail) upsertFolder(info *imap.MailboxInfo, mailbox *imap.MailboxStatus, unread int) (int64, error) {
	special := specialUse(info.Attributes)
	total := int64(mailbox.Messages)
	if unread < 0 {
		unread = 0
	}
	now := Now()
	_, err := m.db.Exec(`INSERT INTO mail_folders (path, name, special_use, uid_validity, last_synced_at, total, unread)
		VALUES (?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(path) DO UPDATE SET name = excluded.name, special_use = excluded.special_use,
		  uid_validity = excluded.uid_validity, last_synced_at = excluded.last_synced_at,
		  total = excluded.total, unread = excluded.unread`,
		info.Name, folderDisplayName(info), nullableText(special), int64(mailbox.UidValidity), now, total, int64(unread))
	if err != nil {
		return 0, err
	}
	var id int64
	if err := m.db.QueryRow(`SELECT id FROM mail_folders WHERE path = ?`, info.Name).Scan(&id); err != nil {
		return 0, err
	}
	return id, nil
}

// countUnseen asks the server for exact UNSEEN uids; some IMAP servers omit
// the UNSEEN value in their SELECT response, which leaves mailbox.Unseen at 0.
func (m *Mail) countUnseen(mailbox *imap.MailboxStatus) int {
	unseen := int(mailbox.Unseen)
	if uids, err := m.client.UidSearch(&imap.SearchCriteria{WithoutFlags: []string{imap.SeenFlag}}); err == nil {
		unseen = len(uids)
	}
	return unseen
}

func (m *Mail) syncFolderMessages(folderID int64, info *imap.MailboxInfo, mailbox *imap.MailboxStatus, processed *int) error {
	uids, err := m.client.UidSearch(&imap.SearchCriteria{})
	if err != nil {
		return err
	}
	limit := 200
	if strings.EqualFold(info.Name, "INBOX") {
		limit = 300
	}
	if len(uids) > limit {
		uids = uids[len(uids)-limit:]
	}
	if len(uids) == 0 {
		return nil
	}
	seqset := new(imap.SeqSet)
	for _, uid := range uids {
		seqset.AddNum(uid)
	}
	items := []imap.FetchItem{
		imap.FetchEnvelope, imap.FetchFlags, imap.FetchRFC822Size,
		imap.FetchUid, imap.FetchBodyStructure,
	}
	messages := make(chan *imap.Message, 64)
	done := make(chan error, 1)
	go func() { done <- m.client.UidFetch(seqset, items, messages) }()
	count := 0
	for message := range messages {
		if err := m.upsertSummary(folderID, message); err != nil {
			continue
		}
		count++
		if count%25 == 0 {
			status := m.statusCopy()
			status.Processed = *processed
			m.setStatus(status)
		}
	}
	return <-done
}

func decodeHeader(value string) string {
	if value == "" {
		return ""
	}
	decoder := &mime.WordDecoder{CharsetReader: charset.Reader}
	decoded, err := decoder.DecodeHeader(value)
	if err != nil {
		return value
	}
	return decoded
}

func convertAddresses(values []*imap.Address) []MailAddress {
	result := make([]MailAddress, 0, len(values))
	for _, value := range values {
		if value == nil {
			continue
		}
		name := decodeHeader(value.PersonalName)
		address := strings.TrimSpace(value.MailboxName + "@" + value.HostName)
		item := MailAddress{}
		if name != "" {
			item.Name = &name
		}
		if address != "" {
			item.Address = &address
		}
		result = append(result, item)
	}
	return result
}

func firstAddress(values []*imap.Address) *MailAddress {
	addresses := convertAddresses(values)
	if len(addresses) == 0 {
		return nil
	}
	return &addresses[0]
}

func bodyHasAttachment(bs *imap.BodyStructure) bool {
	if bs == nil {
		return false
	}
	if strings.EqualFold(bs.Disposition, "attachment") {
		return true
	}
	if bs.Params["name"] != "" || bs.DispositionParams["filename"] != "" {
		return true
	}
	if bs.Id != "" && !strings.HasPrefix(strings.ToLower(bs.MIMEType), "text/") {
		return true
	}
	for _, part := range bs.Parts {
		if bodyHasAttachment(part) {
			return true
		}
	}
	return false
}

func (m *Mail) upsertSummary(folderID int64, message *imap.Message) error {
	if message.Envelope == nil {
		return nil
	}
	envelope := message.Envelope
	subject := decodeHeader(envelope.Subject)
	from := firstAddress(envelope.From)
	toJSON, _ := json.Marshal(convertAddresses(envelope.To))
	ccJSON, _ := json.Marshal(convertAddresses(envelope.Cc))
	seen := false
	flagged := false
	for _, flag := range message.Flags {
		if flag == imap.SeenFlag {
			seen = true
		}
		if flag == imap.FlaggedFlag {
			flagged = true
		}
	}
	var date any
	if !envelope.Date.IsZero() {
		date = envelope.Date.Format(time.RFC3339)
	}
	var size any
	if message.Size > 0 {
		size = int64(message.Size)
	}
	var fromName, fromAddr any
	if from != nil {
		if from.Name != nil {
			fromName = *from.Name
		}
		if from.Address != nil {
			fromAddr = *from.Address
		}
	}
	snippet := subject
	_, err := m.db.Exec(`INSERT INTO mail_messages
		(folder_id, uid, message_id, subject, from_name, from_addr, to_json, cc_json, date, seen, flagged,
		 has_attachments, snippet, size)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(folder_id, uid) DO UPDATE SET
		  message_id = excluded.message_id, subject = excluded.subject, from_name = excluded.from_name,
		  from_addr = excluded.from_addr, to_json = excluded.to_json, cc_json = excluded.cc_json,
		  date = excluded.date, seen = excluded.seen, flagged = excluded.flagged,
		  has_attachments = excluded.has_attachments,
		  snippet = CASE WHEN mail_messages.body_loaded = 1 THEN mail_messages.snippet ELSE excluded.snippet END,
		  size = excluded.size`,
		folderID, message.Uid, nullableText(envelope.MessageId), nullableText(subject),
		fromName, fromAddr, string(toJSON), string(ccJSON), date, BoolInt(seen), BoolInt(flagged),
		BoolInt(bodyHasAttachment(message.BodyStructure)), nullableText(snippet), size)
	return err
}

func (m *Mail) ListFolders() ([]MailFolder, error) {
	rows, err := m.db.Query(`SELECT id, path, name, special_use, total, unread, last_synced_at
		FROM mail_folders ORDER BY CASE WHEN LOWER(path) = 'inbox' THEN 0 ELSE 1 END, name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []MailFolder{}
	for rows.Next() {
		var folder MailFolder
		var special, lastSynced sql.NullString
		var total, unread sql.NullInt64
		if err := rows.Scan(&folder.ID, &folder.Path, &folder.Name, &special, &total, &unread, &lastSynced); err != nil {
			return nil, err
		}
		if special.Valid {
			value := special.String
			folder.SpecialUse = &value
		}
		if total.Valid {
			value := total.Int64
			folder.Total = &value
		}
		if unread.Valid {
			value := unread.Int64
			folder.Unread = &value
		}
		if lastSynced.Valid {
			value := lastSynced.String
			folder.LastSyncedAt = &value
		}
		result = append(result, folder)
	}
	return result, rows.Err()
}

func (m *Mail) ListMessages(folderID int64, query string, limit int) ([]MailMessageSummary, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	sqlText := `SELECT id, uid, subject, from_name, from_addr, date, seen, flagged, has_attachments, snippet, size
		FROM mail_messages WHERE folder_id = ? AND deleted_at IS NULL`
	args := []any{folderID}
	if strings.TrimSpace(query) != "" {
		sqlText += ` AND (subject LIKE ? OR from_name LIKE ? OR from_addr LIKE ? OR snippet LIKE ?)`
		like := "%" + strings.TrimSpace(query) + "%"
		args = append(args, like, like, like, like)
	}
	sqlText += ` ORDER BY date DESC LIMIT ?`
	args = append(args, limit)
	rows, err := m.db.Query(sqlText, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []MailMessageSummary{}
	for rows.Next() {
		summary, err := scanMailSummary(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, summary)
	}
	return result, rows.Err()
}

func scanMailSummary(scanner interface {
	Scan(dest ...any) error
}) (MailMessageSummary, error) {
	var summary MailMessageSummary
	var subject, fromName, fromAddr, date, snippet sql.NullString
	var seen, flagged, attachments int
	var size sql.NullInt64
	if err := scanner.Scan(&summary.ID, &summary.UID, &subject, &fromName, &fromAddr, &date,
		&seen, &flagged, &attachments, &snippet, &size); err != nil {
		return MailMessageSummary{}, err
	}
	if subject.Valid {
		value := subject.String
		summary.Subject = &value
	}
	if fromName.Valid || fromAddr.Valid {
		item := MailAddress{}
		if fromName.Valid {
			value := fromName.String
			item.Name = &value
		}
		if fromAddr.Valid {
			value := fromAddr.String
			item.Address = &value
		}
		summary.Sender = &item
	}
	if date.Valid {
		value := date.String
		summary.Date = &value
	}
	if snippet.Valid {
		value := snippet.String
		summary.Snippet = &value
	}
	if size.Valid {
		value := size.Int64
		summary.Size = &value
	}
	summary.Seen = seen == 1
	summary.Flagged = flagged == 1
	summary.HasAttachments = attachments == 1
	return summary, nil
}

func (m *Mail) Register(router *Router) {
	router.Handle("mail.state", func([]json.RawMessage) (any, error) {
		return m.State()
	})
	router.Handle("mail.configure", func(args []json.RawMessage) (any, error) {
		input, err := DecodeArg[map[string]any](args, 0)
		if err != nil {
			return nil, err
		}
		return m.Configure(input)
	})
	router.Handle("mail.disconnect", func([]json.RawMessage) (any, error) {
		return m.Disconnect()
	})
	router.Handle("mail.sync", func([]json.RawMessage) (any, error) {
		return m.Sync()
	})
	router.Handle("mail.listFolders", func([]json.RawMessage) (any, error) {
		return m.ListFolders()
	})
	router.Handle("mail.folders", func([]json.RawMessage) (any, error) {
		return m.ListFolders()
	})
	router.Handle("mail.listMessages", func(args []json.RawMessage) (any, error) {
		folderID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		query, _, _ := DecodeOptionalArg[string](args, 1)
		limit, _, _ := DecodeOptionalArg[int](args, 2)
		return m.ListMessages(folderID, query, limit)
	})
	router.Handle("mail.getMessage", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		return m.GetMessage(id)
	})
	router.Handle("mail.attachmentUrl", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		part, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return m.AttachmentURL(id, part)
	})
	router.Handle("mail.markSeen", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		seen, err := DecodeArg[bool](args, 1)
		if err != nil {
			return nil, err
		}
		return true, m.MarkSeen(id, seen)
	})
	router.Handle("mail.markFlagged", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		flagged, err := DecodeArg[bool](args, 1)
		if err != nil {
			return nil, err
		}
		return true, m.MarkFlagged(id, flagged)
	})
	router.Handle("mail.deleteMessage", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		return true, m.DeleteMessage(id)
	})
	router.Handle("mail.restoreMessage", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		return true, m.RestoreMessage(id)
	})
	router.Handle("mail.deleteForever", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		return true, m.DeleteForever(id)
	})
	router.Handle("mail.emptyTrash", func([]json.RawMessage) (any, error) {
		return m.EmptyTrash()
	})
	router.Handle("mail.trashList", func([]json.RawMessage) (any, error) {
		return m.TrashList()
	})
	router.Handle("mail.send", func(args []json.RawMessage) (any, error) {
		input, err := DecodeArg[map[string]any](args, 0)
		if err != nil {
			return nil, err
		}
		return m.Send(input)
	})
	router.Handle("mail.saveDraft", func(args []json.RawMessage) (any, error) {
		input, err := DecodeArg[map[string]any](args, 0)
		if err != nil {
			return nil, err
		}
		return m.SaveDraft(input)
	})
	router.Handle("mail.listDrafts", func([]json.RawMessage) (any, error) {
		return m.ListDrafts()
	})
	router.Handle("mail.getDraft", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return m.GetDraft(id)
	})
	router.Handle("mail.deleteDraft", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return true, m.DeleteDraft(id)
	})
}

func (m *Mail) MailboxState() map[string]any {
	state, _ := m.State()
	return map[string]any{"state": state}
}
