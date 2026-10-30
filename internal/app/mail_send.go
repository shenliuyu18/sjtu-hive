package app

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/emersion/go-imap"
	gomail "github.com/wneessen/go-mail"
)

func stringSlice(value any) []string {
	switch typed := value.(type) {
	case []string:
		return typed
	case []any:
		result := make([]string, 0, len(typed))
		for _, item := range typed {
			if item == nil {
				continue
			}
			text := strings.TrimSpace(fmt.Sprint(item))
			if text != "" {
				result = append(result, text)
			}
		}
		return result
	case string:
		replacer := strings.NewReplacer(";", ",", "；", ",", "，", ",", "\n", ",")
		parts := strings.Split(replacer.Replace(typed), ",")
		result := []string{}
		for _, part := range parts {
			if part = strings.TrimSpace(part); part != "" {
				result = append(result, part)
			}
		}
		return result
	}
	return []string{}
}

func (m *Mail) Send(input map[string]any) (MailResult, error) {
	account, err := m.account()
	if err != nil {
		return MailResult{}, err
	}
	if account == nil {
		return MailResult{}, errors.New("尚未配置邮箱")
	}
	credential, err := m.credentials()
	if err != nil {
		return MailResult{}, err
	}
	to := stringSlice(input["to"])
	cc := stringSlice(input["cc"])
	bcc := stringSlice(input["bcc"])
	if len(to)+len(cc)+len(bcc) == 0 {
		return MailResult{}, errors.New("请至少填写一个收件人")
	}
	subject := strings.TrimSpace(inputText(input, "subject"))
	text := inputText(input, "text")
	htmlText := inputText(input, "html")
	if strings.TrimSpace(text) == "" && strings.TrimSpace(htmlText) == "" {
		return MailResult{}, errors.New("邮件正文不能为空")
	}
	if strings.TrimSpace(text) == "" {
		text = stripHTML(htmlText)
	}
	if strings.TrimSpace(subject) == "" {
		subject = "(无主题)"
	}
	message := gomail.NewMsg()
	if err := message.From(account.Address); err != nil {
		return MailResult{}, err
	}
	if len(to) > 0 {
		if err := message.To(to...); err != nil {
			return MailResult{}, errors.New("收件人地址无效：" + err.Error())
		}
	}
	if len(cc) > 0 {
		if err := message.Cc(cc...); err != nil {
			return MailResult{}, errors.New("抄送地址无效：" + err.Error())
		}
	}
	if len(bcc) > 0 {
		if err := message.Bcc(bcc...); err != nil {
			return MailResult{}, errors.New("密送地址无效：" + err.Error())
		}
	}
	message.Subject(subject)
	message.SetBodyString(gomail.TypeTextPlain, text)
	if strings.TrimSpace(htmlText) != "" {
		message.AddAlternativeString(gomail.TypeTextHTML, htmlText)
	}
	for _, attachment := range stringSlice(input["attachments"]) {
		info, err := os.Stat(attachment)
		if err != nil || info.IsDir() {
			return MailResult{}, fmt.Errorf("附件不存在：%s", filepath.Base(attachment))
		}
		if info.Size() > 25<<20 {
			return MailResult{}, fmt.Errorf("附件超过 25 MB：%s", filepath.Base(attachment))
		}
		message.AttachFile(attachment)
	}
	options := []gomail.Option{
		gomail.WithPort(account.SMTPPort),
		gomail.WithTimeout(45 * time.Second),
		gomail.WithSMTPAuth(gomail.SMTPAuthPlain),
		gomail.WithUsername(account.Address),
		gomail.WithPassword(credential.Password),
	}
	if account.SMTPPort == 465 {
		options = append(options, gomail.WithSSL())
	} else {
		options = append(options, gomail.WithTLSPolicy(gomail.TLSMandatory))
	}
	client, err := gomail.NewClient(account.SMTPHost, options...)
	if err != nil {
		return MailResult{}, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := client.DialAndSendWithContext(ctx, message); err != nil {
		return MailResult{}, fmt.Errorf("发送失败：%w", err)
	}
	warning := ""
	if err := m.appendToSent(message); err != nil {
		warning = "邮件已发送，但未能写入服务器「已发送」文件夹：" + err.Error()
	}
	return MailResult{OK: true, Warning: warning}, nil
}

func (m *Mail) appendToSent(message *gomail.Msg) error {
	var buffer bytes.Buffer
	if _, err := message.WriteTo(&buffer); err != nil {
		return err
	}
	m.imapMu.Lock()
	defer m.imapMu.Unlock()
	if err := m.connectLocked(); err != nil {
		return err
	}
	var path string
	err := m.db.QueryRow(`SELECT path FROM mail_folders WHERE special_use = 'sent' LIMIT 1`).Scan(&path)
	if errors.Is(err, sql.ErrNoRows) || path == "" {
		path = "Sent"
	}
	return m.client.Append(path, []string{imap.SeenFlag}, time.Now(), bytes.NewReader(buffer.Bytes()))
}

func (m *Mail) SaveDraft(input map[string]any) (MailDraft, error) {
	id := strings.TrimSpace(inputText(input, "id"))
	if id == "" {
		id = "draft-" + randomID()[:20]
	}
	to, _ := json.Marshal(stringSlice(input["to"]))
	cc, _ := json.Marshal(stringSlice(input["cc"]))
	bcc, _ := json.Marshal(stringSlice(input["bcc"]))
	attachments, _ := json.Marshal(stringSlice(input["attachments"]))
	now := Now()
	_, err := m.db.Exec(`INSERT INTO mail_drafts (id, to_json, cc_json, bcc_json, subject, text, html, attachments_json, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET to_json = excluded.to_json, cc_json = excluded.cc_json,
		  bcc_json = excluded.bcc_json, subject = excluded.subject, text = excluded.text,
		  html = excluded.html, attachments_json = excluded.attachments_json, updated_at = excluded.updated_at`,
		id, string(to), string(cc), string(bcc), inputText(input, "subject"),
		inputText(input, "text"), inputText(input, "html"), string(attachments), now)
	if err != nil {
		return MailDraft{}, err
	}
	return m.GetDraft(id)
}

func (m *Mail) GetDraft(id string) (MailDraft, error) {
	var draft MailDraft
	var to, cc, bcc, attachments string
	err := m.db.QueryRow(`SELECT id, to_json, cc_json, bcc_json, subject, text, html, attachments_json, updated_at
		FROM mail_drafts WHERE id = ?`, id).
		Scan(&draft.ID, &to, &cc, &bcc, &draft.Subject, &draft.Text, &draft.HTML, &attachments, &draft.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return MailDraft{}, errors.New("草稿不存在")
	}
	if err != nil {
		return MailDraft{}, err
	}
	_ = json.Unmarshal([]byte(to), &draft.To)
	_ = json.Unmarshal([]byte(cc), &draft.Cc)
	_ = json.Unmarshal([]byte(bcc), &draft.Bcc)
	_ = json.Unmarshal([]byte(attachments), &draft.Attachments)
	if draft.To == nil {
		draft.To = []string{}
	}
	if draft.Cc == nil {
		draft.Cc = []string{}
	}
	if draft.Bcc == nil {
		draft.Bcc = []string{}
	}
	if draft.Attachments == nil {
		draft.Attachments = []string{}
	}
	return draft, nil
}

func (m *Mail) ListDrafts() ([]MailDraftSummary, error) {
	rows, err := m.db.Query(`SELECT id, to_json, subject, updated_at, attachments_json
		FROM mail_drafts ORDER BY updated_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []MailDraftSummary{}
	for rows.Next() {
		var item MailDraftSummary
		var toJSON, attachmentsJSON string
		if err := rows.Scan(&item.ID, &toJSON, &item.Subject, &item.UpdatedAt, &attachmentsJSON); err != nil {
			return nil, err
		}
		_ = json.Unmarshal([]byte(toJSON), &item.To)
		if item.To == nil {
			item.To = []string{}
		}
		var attachments []string
		_ = json.Unmarshal([]byte(attachmentsJSON), &attachments)
		item.Attachments = len(attachments)
		result = append(result, item)
	}
	return result, rows.Err()
}

func (m *Mail) DeleteDraft(id string) error {
	_, err := m.db.Exec(`DELETE FROM mail_drafts WHERE id = ?`, id)
	return err
}

func (m *Mail) DeleteMessage(id int64) error {
	_, err := m.db.Exec(`UPDATE mail_messages SET deleted_at = ?, original_folder_id = folder_id
		WHERE id = ? AND deleted_at IS NULL`, Now(), id)
	if err == nil {
		m.emitMailChanged()
	}
	return err
}

func (m *Mail) RestoreMessage(id int64) error {
	_, err := m.db.Exec(`UPDATE mail_messages SET deleted_at = NULL, original_folder_id = NULL WHERE id = ?`, id)
	if err == nil {
		m.emitMailChanged()
	}
	return err
}

func (m *Mail) DeleteForever(id int64) error {
	row, err := m.loadMessageRow(id)
	if err != nil {
		return err
	}
	m.removeAttachmentFiles(row)
	if _, err := m.db.Exec(`DELETE FROM mail_messages WHERE id = ?`, id); err != nil {
		return err
	}
	m.emitMailChanged()
	return nil
}

func (m *Mail) removeAttachmentFiles(row mailMessageRow) {
	var attachments []MailAttachmentInfo
	_ = json.Unmarshal([]byte(row.AttachmentsJSON), &attachments)
	for _, attachment := range attachments {
		if attachment.LocalPath != "" {
			_ = os.Remove(attachment.LocalPath)
		}
	}
	_ = os.RemoveAll(filepath.Join(m.paths.MailDir, fmt.Sprintf("%d", row.ID)))
}

func (m *Mail) EmptyTrash() (int, error) {
	rows, err := m.db.Query(`SELECT id, attachments_json FROM mail_messages WHERE deleted_at IS NOT NULL`)
	if err != nil {
		return 0, err
	}
	ids := []int64{}
	for rows.Next() {
		var id int64
		var attachmentsJSON string
		if rows.Scan(&id, &attachmentsJSON) == nil {
			var attachments []MailAttachmentInfo
			_ = json.Unmarshal([]byte(attachmentsJSON), &attachments)
			for _, attachment := range attachments {
				if attachment.LocalPath != "" {
					_ = os.Remove(attachment.LocalPath)
				}
			}
			_ = os.RemoveAll(filepath.Join(m.paths.MailDir, fmt.Sprintf("%d", id)))
			ids = append(ids, id)
		}
	}
	_ = rows.Close()
	for _, id := range ids {
		_, _ = m.db.Exec(`DELETE FROM mail_messages WHERE id = ?`, id)
	}
	m.emitMailChanged()
	return len(ids), nil
}

func (m *Mail) TrashList() ([]MailTrashItem, error) {
	rows, err := m.db.Query(`SELECT m.id, m.uid, m.subject, m.from_name, m.from_addr, m.date, m.seen, m.flagged,
		m.has_attachments, m.snippet, m.size, f.name, m.deleted_at
		FROM mail_messages m JOIN mail_folders f ON f.id = m.folder_id
		WHERE m.deleted_at IS NOT NULL ORDER BY m.deleted_at DESC LIMIT 500`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []MailTrashItem{}
	for rows.Next() {
		var item MailTrashItem
		var subject, fromName, fromAddr, date, snippet, folderName, deletedAt sql.NullString
		var seen, flagged, attachments int
		var size sql.NullInt64
		if err := rows.Scan(&item.ID, &item.UID, &subject, &fromName, &fromAddr, &date, &seen, &flagged,
			&attachments, &snippet, &size, &folderName, &deletedAt); err != nil {
			return nil, err
		}
		if subject.Valid {
			value := subject.String
			item.Subject = &value
		}
		if fromName.Valid || fromAddr.Valid {
			address := MailAddress{}
			if fromName.Valid {
				value := fromName.String
				address.Name = &value
			}
			if fromAddr.Valid {
				value := fromAddr.String
				address.Address = &value
			}
			item.Sender = &address
		}
		if date.Valid {
			value := date.String
			item.Date = &value
		}
		if snippet.Valid {
			value := snippet.String
			item.Snippet = &value
		}
		if size.Valid {
			value := size.Int64
			item.Size = &value
		}
		item.Seen = seen == 1
		item.Flagged = flagged == 1
		item.HasAttachments = attachments == 1
		item.FolderName = folderName.String
		item.DeletedAt = deletedAt.String
		result = append(result, item)
	}
	return result, rows.Err()
}

func (m *Mail) CleanupTrash(days int) {
	if days <= 0 {
		return
	}
	cutoff := time.Now().AddDate(0, 0, -days).Format(time.RFC3339)
	rows, err := m.db.Query(`SELECT id, attachments_json FROM mail_messages
		WHERE deleted_at IS NOT NULL AND deleted_at < ?`, cutoff)
	if err != nil {
		return
	}
	ids := []int64{}
	for rows.Next() {
		var id int64
		var attachmentsJSON string
		if rows.Scan(&id, &attachmentsJSON) == nil {
			var attachments []MailAttachmentInfo
			_ = json.Unmarshal([]byte(attachmentsJSON), &attachments)
			for _, attachment := range attachments {
				if attachment.LocalPath != "" {
					_ = os.Remove(attachment.LocalPath)
				}
			}
			_ = os.RemoveAll(filepath.Join(m.paths.MailDir, fmt.Sprintf("%d", id)))
			ids = append(ids, id)
		}
	}
	_ = rows.Close()
	for _, id := range ids {
		_, _ = m.db.Exec(`DELETE FROM mail_messages WHERE id = ?`, id)
	}
}

func (m *Mail) emitMailChanged() {
	if m.emit != nil {
		if state, err := m.State(); err == nil {
			m.emit("mail.state", state)
		}
	}
}

func (m *Mail) StartBackground(ctx context.Context, interval func() time.Duration) {
	go func() {
		for {
			account, _ := m.account()
			credential, _ := m.credentials()
			if account != nil && credential != nil {
				_, _ = m.Sync()
			}
			wait := 5 * time.Minute
			if interval != nil {
				if value := interval(); value > 0 {
					wait = value
				}
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(wait):
			}
		}
	}()
}
