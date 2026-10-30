package app

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"strings"
)

type ShuiyuanProfileBadge struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	Icon        string `json:"icon"`
	ImageURL    string `json:"imageUrl"`
	GrantedAt   string `json:"grantedAt"`
	Count       int64  `json:"count"`
	BadgeType   string `json:"badgeType"`
}

type ShuiyuanProfile struct {
	ID               int64                  `json:"id"`
	Username         string                 `json:"username"`
	Name             string                 `json:"name"`
	AvatarTemplate   string                 `json:"avatarTemplate"`
	Title            string                 `json:"title"`
	BioRaw           string                 `json:"bioRaw"`
	BioCooked        string                 `json:"bioCooked"`
	TrustLevel       int64                  `json:"trustLevel"`
	Admin            bool                   `json:"admin"`
	Moderator        bool                   `json:"moderator"`
	ProfileHidden    bool                   `json:"profileHidden"`
	CreatedAt        string                 `json:"createdAt"`
	CakeDate         string                 `json:"cakedate"`
	LastSeenAt       string                 `json:"lastSeenAt"`
	LastPostedAt     string                 `json:"lastPostedAt"`
	ProfileViewCount int64                  `json:"profileViewCount"`
	BadgeCount       int64                  `json:"badgeCount"`
	AcceptedAnswers  int64                  `json:"acceptedAnswers"`
	PrimaryGroup     string                 `json:"primaryGroup"`
	Groups           []string               `json:"groups"`
	IsSelf           bool                   `json:"isSelf"`
	Badges           []ShuiyuanProfileBadge `json:"badges"`
}

type ShuiyuanActivityItem struct {
	ID         int64  `json:"id"`
	Kind       string `json:"kind"`
	TopicID    int64  `json:"topicId"`
	TopicTitle string `json:"topicTitle"`
	PostNumber int64  `json:"postNumber"`
	Blurb      string `json:"blurb"`
	CreatedAt  string `json:"createdAt"`
}

type shuiyuanBadgeInfo struct {
	Name        string
	Description string
	Icon        string
	ImageURL    string
	TypeID      int64
}

type shuiyuanActivityPost struct {
	TopicID    int64
	PostNumber int64
	Blurb      string
	CreatedAt  string
}

func (s *Shuiyuan) GetProfile(username string) (ShuiyuanProfile, error) {
	username = strings.TrimSpace(username)
	if username == "" {
		return ShuiyuanProfile{}, errors.New("用户名不能为空")
	}
	data, err := s.request(http.MethodGet, "/u/"+url.PathEscape(username)+".json", nil)
	if err != nil {
		return ShuiyuanProfile{}, err
	}
	selfName := ""
	if credential, err := s.credential(); err == nil {
		selfName = credential.Username
	}
	return mapShuiyuanProfile(data, selfName, s.rewriteAssets)
}

func mapShuiyuanProfile(data []byte, selfName string, rewrite func(string) string) (ShuiyuanProfile, error) {
	var raw struct {
		User       json.RawMessage `json:"user"`
		UserBadges []struct {
			BadgeID   int64  `json:"badge_id"`
			GrantedAt string `json:"granted_at"`
			Count     int64  `json:"count"`
		} `json:"user_badges"`
		Badges []struct {
			ID          int64  `json:"id"`
			Name        string `json:"name"`
			Description string `json:"description"`
			Icon        string `json:"icon"`
			ImageURL    string `json:"image_url"`
			BadgeTypeID int64  `json:"badge_type_id"`
		} `json:"badges"`
		BadgeTypes []struct {
			ID   int64  `json:"id"`
			Name string `json:"name"`
		} `json:"badge_types"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return ShuiyuanProfile{}, errors.New("水源用户资料格式无法识别")
	}
	var user struct {
		ID               int64    `json:"id"`
		Username         string   `json:"username"`
		Name             string   `json:"name"`
		AvatarTemplate   string   `json:"avatar_template"`
		Title            string   `json:"title"`
		BioRaw           string   `json:"bio_raw"`
		BioCooked        string   `json:"bio_cooked"`
		TrustLevel       int64    `json:"trust_level"`
		Admin            bool     `json:"admin"`
		Moderator        bool     `json:"moderator"`
		ProfileHidden    bool     `json:"profile_hidden"`
		CreatedAt        string   `json:"created_at"`
		CakeDate         string   `json:"cakedate"`
		LastSeenAt       string   `json:"last_seen_at"`
		LastPostedAt     string   `json:"last_posted_at"`
		ProfileViewCount int64    `json:"profile_view_count"`
		BadgeCount       int64    `json:"badge_count"`
		AcceptedAnswers  int64    `json:"accepted_answers"`
		PrimaryGroupName string   `json:"primary_group_name"`
		Groups           []struct {
			Name string `json:"name"`
		} `json:"groups"`
	}
	if err := json.Unmarshal(raw.User, &user); err != nil {
		return ShuiyuanProfile{}, errors.New("水源用户资料格式无法识别")
	}
	if user.Username == "" {
		return ShuiyuanProfile{}, errors.New("找不到该水源用户")
	}

	profile := ShuiyuanProfile{
		ID:               user.ID,
		Username:         user.Username,
		Name:             user.Name,
		AvatarTemplate:   user.AvatarTemplate,
		Title:            user.Title,
		BioRaw:           user.BioRaw,
		BioCooked:        rewriteOrSame(rewrite, user.BioCooked),
		TrustLevel:       user.TrustLevel,
		Admin:            user.Admin,
		Moderator:        user.Moderator,
		ProfileHidden:    user.ProfileHidden,
		CreatedAt:        user.CreatedAt,
		CakeDate:         user.CakeDate,
		LastSeenAt:       user.LastSeenAt,
		LastPostedAt:     user.LastPostedAt,
		ProfileViewCount: user.ProfileViewCount,
		BadgeCount:       user.BadgeCount,
		AcceptedAnswers:  user.AcceptedAnswers,
		PrimaryGroup:     user.PrimaryGroupName,
		Groups:           make([]string, 0, len(user.Groups)),
		Badges:           make([]ShuiyuanProfileBadge, 0, len(raw.UserBadges)),
	}
	for _, group := range user.Groups {
		name := strings.TrimSpace(group.Name)
		if name == "" || shuiyuanInternalGroup.MatchString(name) {
			continue
		}
		profile.Groups = append(profile.Groups, name)
	}
	profile.IsSelf = selfName != "" && strings.EqualFold(selfName, profile.Username)

	infos := make(map[int64]shuiyuanBadgeInfo, len(raw.Badges))
	for _, badge := range raw.Badges {
		infos[badge.ID] = shuiyuanBadgeInfo{
			Name:        badge.Name,
			Description: stripShuiyuanHTML(badge.Description),
			Icon:        badge.Icon,
			ImageURL:    badge.ImageURL,
			TypeID:      badge.BadgeTypeID,
		}
	}
	typeNames := make(map[int64]string, len(raw.BadgeTypes))
	for _, badgeType := range raw.BadgeTypes {
		typeNames[badgeType.ID] = badgeType.Name
	}
	for _, granted := range raw.UserBadges {
		info, ok := infos[granted.BadgeID]
		if !ok {
			continue
		}
		profile.Badges = append(profile.Badges, ShuiyuanProfileBadge{
			ID:          granted.BadgeID,
			Name:        info.Name,
			Description: info.Description,
			Icon:        info.Icon,
			ImageURL:    info.ImageURL,
			GrantedAt:   granted.GrantedAt,
			Count:       granted.Count,
			BadgeType:   typeNames[info.TypeID],
		})
	}
	return profile, nil
}

func (s *Shuiyuan) GetActivity(username string) ([]ShuiyuanActivityItem, error) {
	username = strings.TrimSpace(username)
	if username == "" {
		return nil, errors.New("用户名不能为空")
	}
	data, err := s.request(http.MethodGet, "/u/"+url.PathEscape(username)+"/activity/newest.json?page=0", nil)
	if err != nil {
		return nil, err
	}
	return mapShuiyuanActivity(data)
}

func mapShuiyuanActivity(data []byte) ([]ShuiyuanActivityItem, error) {
	var raw struct {
		Stream []json.RawMessage `json:"stream"`
		Posts  []struct {
			ID         int64  `json:"id"`
			TopicID    int64  `json:"topic_id"`
			PostNumber int64  `json:"post_number"`
			Blurb      string `json:"blurb"`
			CreatedAt  string `json:"created_at"`
		} `json:"posts"`
		Topics []struct {
			ID    int64  `json:"id"`
			Title string `json:"title"`
		} `json:"topics"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, errors.New("水源用户动态格式无法识别")
	}
	posts := make(map[int64]shuiyuanActivityPost, len(raw.Posts))
	for _, post := range raw.Posts {
		posts[post.ID] = shuiyuanActivityPost{
			TopicID: post.TopicID, PostNumber: post.PostNumber, Blurb: post.Blurb, CreatedAt: post.CreatedAt,
		}
	}
	titles := make(map[int64]string, len(raw.Topics))
	for _, topic := range raw.Topics {
		titles[topic.ID] = topic.Title
	}

	items := make([]ShuiyuanActivityItem, 0, len(raw.Stream))
	for _, entry := range raw.Stream {
		id, createdAt, actionType, postNumber, topicID := decodeActivityEntry(entry)
		if id == 0 && createdAt == "" {
			continue
		}
		item := ShuiyuanActivityItem{ID: id, CreatedAt: createdAt, PostNumber: postNumber, TopicID: topicID}
		if post, ok := posts[id]; ok {
			if item.TopicID == 0 {
				item.TopicID = post.TopicID
			}
			if item.PostNumber == 0 {
				item.PostNumber = post.PostNumber
			}
			if item.CreatedAt == "" {
				item.CreatedAt = post.CreatedAt
			}
			item.Blurb = stripShuiyuanHTML(post.Blurb)
		}
		if item.PostNumber == 0 && item.Blurb == "" {
			continue
		}
		item.Kind = activityKind(actionType, item.PostNumber)
		item.TopicTitle = titles[item.TopicID]
		items = append(items, item)
	}
	return items, nil
}

func decodeActivityEntry(entry json.RawMessage) (id int64, createdAt string, actionType int64, postNumber int64, topicID int64) {
	var number int64
	if err := json.Unmarshal(entry, &number); err == nil {
		return number, "", 0, 0, 0
	}
	var object struct {
		ID         int64  `json:"id"`
		CreatedAt  string `json:"created_at"`
		ActionType int64  `json:"action_type"`
		PostNumber int64  `json:"post_number"`
		TopicID    int64  `json:"topic_id"`
	}
	if err := json.Unmarshal(entry, &object); err != nil {
		return 0, "", 0, 0, 0
	}
	return object.ID, object.CreatedAt, object.ActionType, object.PostNumber, object.TopicID
}

func activityKind(actionType, postNumber int64) string {
	switch actionType {
	case 1, 2:
		return "like"
	case 5:
		return "mention"
	case 8:
		return "message"
	}
	if postNumber <= 1 {
		return "topic"
	}
	return "reply"
}

var shuiyuanTagPattern = regexp.MustCompile(`(?is)<[^>]+>`)

// Discourse auto-created groups carry no meaning for a reader of the profile.
var shuiyuanInternalGroup = regexp.MustCompile(`(?i)^(trust_level_\d+|g-[a-z0-9_-]+|everyone|lurkers)$`)

func stripShuiyuanHTML(value string) string {
	if value == "" {
		return ""
	}
	plain := shuiyuanTagPattern.ReplaceAllString(value, " ")
	plain = strings.NewReplacer("&nbsp;", " ", "&amp;", "&", "&lt;", "<", "&gt;", ">", "&quot;", `"`, "&#39;", "'").
		Replace(plain)
	return strings.Join(strings.Fields(plain), " ")
}
