package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/emersion/go-imap"
	mimemail "github.com/emersion/go-message/mail"
	"github.com/microcosm-cc/bluemonday"
)

type mailMessageRow struct {
	ID              int64
	FolderID        int64
	FolderPath      string
	UID             uint32
	Subject         *string
	Sender          *MailAddress
	Date            *string
	Seen            bool
	Flagged         bool
	HasAttachments  bool
	Snippet         *string
	Size            *int64
	BodyLoaded      bool
	BodyText        *string
	BodyHTML        *string
	AttachmentsJSON string
	ToJSON          string
	CcJSON          string
}

func (m *Mail) loadMessageRow(id int64) (mailMessageRow, error) {
	var row mailMessageRow
	var subject, fromName, fromAddr, date, snippet, bodyText, bodyHTML, attachments, toJSON, ccJSON sql.NullString
	var size sql.NullInt64
	var seen, flagged, hasAttachments, bodyLoaded int
	err := m.db.QueryRow(`SELECT m.id, m.folder_id, f.path, m.uid, m.subject, m.from_name, m.from_addr,
		m.date, m.seen, m.flagged, m.has_attachments, m.snippet, m.size, m.body_loaded,
		m.body_text, m.body_html, m.attachments_json, m.to_json, m.cc_json
		FROM mail_messages m JOIN mail_folders f ON f.id = m.folder_id WHERE m.id = ?`, id).
		Scan(&row.ID, &row.FolderID, &row.FolderPath, &row.UID, &subject, &fromName, &fromAddr,
			&date, &seen, &flagged, &hasAttachments, &snippet, &size, &bodyLoaded,
			&bodyText, &bodyHTML, &attachments, &toJSON, &ccJSON)
	if errors.Is(err, sql.ErrNoRows) {
		return mailMessageRow{}, errors.New("邮件不存在")
	}
	if err != nil {
		return mailMessageRow{}, err
	}
	if subject.Valid {
		value := subject.String
		row.Subject = &value
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
		row.Sender = &item
	}
	if date.Valid {
		value := date.String
		row.Date = &value
	}
	if snippet.Valid {
		value := snippet.String
		row.Snippet = &value
	}
	if size.Valid {
		value := size.Int64
		row.Size = &value
	}
	if bodyText.Valid {
		value := bodyText.String
		row.BodyText = &value
	}
	if bodyHTML.Valid {
		value := bodyHTML.String
		row.BodyHTML = &value
	}
	row.Seen = seen == 1
	row.Flagged = flagged == 1
	row.HasAttachments = hasAttachments == 1
	row.BodyLoaded = bodyLoaded == 1
	row.AttachmentsJSON = attachments.String
	row.ToJSON = toJSON.String
	row.CcJSON = ccJSON.String
	return row, nil
}

func (m *Mail) GetMessage(id int64) (MailMessageDetail, error) {
	row, err := m.loadMessageRow(id)
	if err != nil {
		return MailMessageDetail{}, err
	}
	if !row.BodyLoaded {
		m.imapMu.Lock()
		err := m.fetchBodyLocked(row)
		if err != nil {
			m.closeClientLocked()
		}
		m.imapMu.Unlock()
		if err != nil {
			return MailMessageDetail{}, fmt.Errorf("无法读取邮件正文：%w", err)
		}
		row, err = m.loadMessageRow(id)
		if err != nil {
			return MailMessageDetail{}, err
		}
	}
	return m.detailFromRow(row), nil
}

func (m *Mail) fetchBodyLocked(row mailMessageRow) error {
	if err := m.connectLocked(); err != nil {
		return err
	}
	if _, err := m.client.Select(row.FolderPath, false); err != nil {
		return err
	}
	section := &imap.BodySectionName{}
	seqset := new(imap.SeqSet)
	seqset.AddNum(row.UID)
	messages := make(chan *imap.Message, 1)
	done := make(chan error, 1)
	go func() {
		done <- m.client.UidFetch(seqset, []imap.FetchItem{section.FetchItem()}, messages)
	}()
	var body io.Reader
	for message := range messages {
		body = message.GetBody(section)
	}
	if err := <-done; err != nil {
		return err
	}
	if body == nil {
		return errors.New("服务器没有返回正文")
	}
	bodyText, bodyHTML, attachments, snippet := m.parseBody(row, body)
	attachmentsJSON, _ := json.Marshal(attachments)
	_, err := m.db.Exec(`UPDATE mail_messages SET body_loaded = 1, body_text = ?, body_html = ?,
		attachments_json = ?, snippet = ?, has_attachments = ? WHERE id = ?`,
		nullableText(bodyText), nullableText(bodyHTML), string(attachmentsJSON),
		nullableText(snippet), BoolInt(len(attachments) > 0), row.ID)
	return err
}

type mailPartHeader interface {
	ContentType() (string, map[string]string, error)
	ContentDisposition() (string, map[string]string, error)
	Get(string) string
}

var mailSanitizer = func() *bluemonday.Policy {
	policy := bluemonday.UGCPolicy()
	policy.AllowImages()
	policy.RequireNoReferrerOnLinks(true)
	return policy
}()

func sanitizeMailHTML(value string) string {
	if strings.TrimSpace(value) == "" {
		return ""
	}
	return mailSanitizer.Sanitize(value)
}

var (
	styleBlockPattern = regexp.MustCompile(`(?is)<(?:style|script)[^>]*>.*?</(?:style|script)>`)
	tagPattern        = regexp.MustCompile(`(?s)<[^>]+>`)
	spacePattern      = regexp.MustCompile(`\s+`)
)

func stripHTML(value string) string {
	value = styleBlockPattern.ReplaceAllString(value, " ")
	value = tagPattern.ReplaceAllString(value, " ")
	value = html.UnescapeString(value)
	return strings.TrimSpace(spacePattern.ReplaceAllString(value, " "))
}

func truncateRunes(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit])
}

func (m *Mail) parseBody(row mailMessageRow, body io.Reader) (string, string, []MailAttachmentInfo, string) {
	reader, err := mimemail.CreateReader(body)
	if err != nil && reader == nil {
		return "", "", nil, ""
	}
	bodyText := ""
	bodyHTML := ""
	attachments := []MailAttachmentInfo{}
	index := 0
	for {
		part, err := reader.NextPart()
		if err == io.EOF {
			break
		}
		if part == nil {
			break
		}
		index++
		header, ok := part.Header.(mailPartHeader)
		if !ok {
			continue
		}
		contentType, ctypeParams, _ := header.ContentType()
		disposition, dispParams, _ := header.ContentDisposition()
		contentID := strings.Trim(header.Get("Content-ID"), "<> \t")
		filename := strings.TrimSpace(dispParams["filename"])
		if filename == "" {
			filename = strings.TrimSpace(ctypeParams["name"])
		}
		lowerType := strings.ToLower(contentType)
		if strings.HasPrefix(lowerType, "text/plain") && filename == "" && bodyText == "" {
			data, _ := io.ReadAll(io.LimitReader(part.Body, 4<<20))
			bodyText = string(data)
			continue
		}
		if strings.HasPrefix(lowerType, "text/html") && filename == "" && bodyHTML == "" {
			data, _ := io.ReadAll(io.LimitReader(part.Body, 8<<20))
			bodyHTML = string(data)
			continue
		}
		inline := strings.EqualFold(disposition, "inline") || (contentID != "" && strings.HasPrefix(lowerType, "image/"))
		isAttachment := filename != "" || strings.EqualFold(disposition, "attachment") || (contentID != "" && !strings.HasPrefix(lowerType, "text/") && !strings.Contains(lowerType, "multipart"))
		if !isAttachment {
			continue
		}
		if filename == "" {
			filename = fmt.Sprintf("attachment-%d", index)
		}
		data, err := io.ReadAll(io.LimitReader(part.Body, 25<<20))
		if err != nil {
			continue
		}
		partID := fmt.Sprintf("part-%d", index)
		dir := filepath.Join(m.paths.MailDir, strconv.FormatInt(row.ID, 10))
		if err := os.MkdirAll(dir, 0o755); err != nil {
			continue
		}
		localPath := filepath.Join(dir, partID+"-"+sanitizeFilename(filename, partID))
		if err := os.WriteFile(localPath, data, 0o644); err != nil {
			continue
		}
		attachments = append(attachments, MailAttachmentInfo{
			Filename: filename, Size: int64(len(data)), Part: partID,
			ContentID: contentID, Inline: inline, ContentType: contentType, LocalPath: localPath,
		})
	}
	if bodyText == "" && bodyHTML == "" {
		// 极少数邮件只有附件或非标准正文。
	}
	snippet := truncateRunes(stripHTML(bodyHTML), 160)
	if bodyText != "" {
		snippet = truncateRunes(strings.TrimSpace(spacePattern.ReplaceAllString(bodyText, " ")), 160)
	}
	return bodyText, bodyHTML, attachments, snippet
}

// attachmentPath derives the on-disk attachment path for rows whose JSON predates
// LocalPath persistence; attachments are always stored as <part>-<filename>.
func (m *Mail) attachmentPath(messageID int64, attachment MailAttachmentInfo) string {
	if attachment.LocalPath != "" {
		return attachment.LocalPath
	}
	if attachment.Part == "" {
		return ""
	}
	return filepath.Join(
		m.paths.MailDir,
		strconv.FormatInt(messageID, 10),
		attachment.Part+"-"+sanitizeFilename(attachment.Filename, attachment.Part),
	)
}

func (m *Mail) detailFromRow(row mailMessageRow) MailMessageDetail {
	detail := MailMessageDetail{
		MailMessageSummary: MailMessageSummary{
			ID: row.ID, UID: row.UID, Subject: row.Subject, Sender: row.Sender, Date: row.Date,
			Seen: row.Seen, Flagged: row.Flagged, HasAttachments: row.HasAttachments,
			Snippet: row.Snippet, Size: row.Size,
		},
		FolderID: row.FolderID, To: []MailAddress{}, Cc: []MailAddress{},
		BodyText: row.BodyText, BodyHTML: row.BodyHTML, Attachments: []MailAttachmentInfo{},
	}
	if row.ToJSON != "" {
		_ = json.Unmarshal([]byte(row.ToJSON), &detail.To)
	}
	if row.CcJSON != "" {
		_ = json.Unmarshal([]byte(row.CcJSON), &detail.Cc)
	}
	if row.AttachmentsJSON != "" {
		_ = json.Unmarshal([]byte(row.AttachmentsJSON), &detail.Attachments)
	}
	if detail.BodyHTML != nil && *detail.BodyHTML != "" {
		body := *detail.BodyHTML
		for _, attachment := range detail.Attachments {
			localPath := m.attachmentPath(row.ID, attachment)
			if localPath == "" {
				continue
			}
			url, err := m.media.AddFile(localPath, attachment.Filename, attachment.ContentType, time.Hour)
			if err != nil {
				continue
			}
			if attachment.Inline && attachment.ContentID != "" {
				cid := attachment.ContentID
				body = strings.ReplaceAll(body, "cid:<"+cid+">", url)
				body = strings.ReplaceAll(body, "cid:"+cid, url)
			}
		}
		body = sanitizeMailHTML(body)
		detail.BodyHTML = &body
	}
	for index := range detail.Attachments {
		attachment := &detail.Attachments[index]
		localPath := m.attachmentPath(row.ID, *attachment)
		if localPath == "" {
			continue
		}
		if url, err := m.media.AddFile(localPath, attachment.Filename, attachment.ContentType, time.Hour); err == nil {
			attachment.URL = url
		}
		attachment.LocalPath = ""
	}
	return detail
}

// PreviewAttachment exposes an attachment inline so the renderer can preview it.
func (m *Mail) PreviewAttachment(messageID int64, part string) (DocumentContent, error) {
	row, err := m.loadMessageRow(messageID)
	if err != nil {
		return DocumentContent{}, err
	}
	var attachments []MailAttachmentInfo
	_ = json.Unmarshal([]byte(row.AttachmentsJSON), &attachments)
	for _, attachment := range attachments {
		localPath := m.attachmentPath(messageID, attachment)
		if attachment.Part != part || localPath == "" {
			continue
		}
		ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(attachment.Filename)), ".")
		contentType := attachment.ContentType
		if known, ok := binaryExtensions[ext]; ok && known != "" {
			contentType = known
		}
		resource, err := m.media.AddFile(localPath, attachment.Filename, contentType, time.Hour)
		if err != nil {
			return DocumentContent{}, err
		}
		format := formatOf(attachment.Filename, ext, false)
		content := DocumentContent{
			Title: attachment.Filename, Size: attachment.Size, Format: format,
			ResourceURL: resource, ReadOnly: true, ModifiedAt: Now(),
		}
		if format == "binary" {
			content.Message = "该附件格式暂不支持内嵌预览，请下载后打开。"
		}
		return content, nil
	}
	return DocumentContent{}, errors.New("附件不存在")
}

func (m *Mail) AttachmentURL(messageID int64, part string) (map[string]string, error) {
	row, err := m.loadMessageRow(messageID)
	if err != nil {
		return nil, err
	}
	var attachments []MailAttachmentInfo
	_ = json.Unmarshal([]byte(row.AttachmentsJSON), &attachments)
	for _, attachment := range attachments {
		localPath := m.attachmentPath(messageID, attachment)
		if attachment.Part != part || localPath == "" {
			continue
		}
		url, err := m.media.AddFile(localPath, attachment.Filename, attachment.ContentType, time.Hour)
		if err != nil {
			return nil, err
		}
		return map[string]string{"url": url + "?download=1", "filename": attachment.Filename}, nil
	}
	return nil, errors.New("附件不存在")
}

func (m *Mail) MarkSeen(id int64, seen bool) error {
	return m.setFlag(id, imap.SeenFlag, seen, "seen")
}

func (m *Mail) MarkFlagged(id int64, flagged bool) error {
	return m.setFlag(id, imap.FlaggedFlag, flagged, "flagged")
}

func (m *Mail) setFlag(id int64, flag string, value bool, column string) error {
	row, err := m.loadMessageRow(id)
	if err != nil {
		return err
	}
	m.imapMu.Lock()
	remoteErr := m.setRemoteFlagLocked(row, flag, value)
	if remoteErr != nil {
		m.closeClientLocked()
	}
	m.imapMu.Unlock()
	_, err = m.db.Exec(fmt.Sprintf(`UPDATE mail_messages SET %s = ? WHERE id = ?`, column), BoolInt(value), id)
	if err != nil {
		return err
	}
	if remoteErr != nil {
		return fmt.Errorf("服务器同步失败：%w", remoteErr)
	}
	return nil
}

func (m *Mail) setRemoteFlagLocked(row mailMessageRow, flag string, value bool) error {
	if err := m.connectLocked(); err != nil {
		return err
	}
	if _, err := m.client.Select(row.FolderPath, false); err != nil {
		return err
	}
	seqset := new(imap.SeqSet)
	seqset.AddNum(row.UID)
	var item imap.StoreItem = imap.AddFlags
	if !value {
		item = imap.RemoveFlags
	}
	return m.client.UidStore(seqset, item, []interface{}{flag}, nil)
}
