package app

import (
	"encoding/json"
	"strings"
	"testing"
)

const shuiyuanProfileFixture = `{
  "user": {
    "id": 120081, "username": "申流域", "name": "东川路用户", "avatar_template": "/user_avatar/shuiyuan.sjtu.edu.cn/x/{size}/1.png",
    "title": "潜水员", "bio_raw": "你好", "bio_cooked": "<img src=\"https://shuiyuan.sjtu.edu.cn/secure-uploads/original/4X/a/b/c/sign.png\">",
    "trust_level": 2, "admin": false, "moderator": false, "email": "secret@example.com",
    "created_at": "2025-01-02T03:04:05.000Z", "cakedate": "2025-01-02T00:00:00.000Z",
    "last_seen_at": "2026-10-29T03:00:00.000Z", "profile_view_count": 42, "badge_count": 3, "accepted_answers": 7,
    "primary_group_name": "JTU", "groups": [{"name": "JTU"}, {"name": " "}, {"name": "trust_level_1"}, {"name": "g-category"}], "profile_hidden": false
  },
  "user_badges": [
    {"id": 1, "badge_id": 1, "granted_at": "2026-08-31T03:50:26.423Z", "count": 1},
    {"id": 2, "badge_id": 5, "granted_at": "2026-08-31T04:03:05.375Z", "count": 2},
    {"id": 3, "badge_id": 999, "granted_at": "2026-08-31T04:03:05.375Z", "count": 1}
  ],
  "badges": [
    {"id": 1, "name": "初级用户", "description": "<a href=\"https://blog.discourse.org\">授予</a>基础功能", "icon": "user", "badge_type_id": 1},
    {"id": 5, "name": "读者", "description": "阅读过很多帖子", "icon": "book", "badge_type_id": 2}
  ],
  "badge_types": [{"id": 1, "name": "青铜"}, {"id": 2, "name": "银"}]
}`

func TestShuiyuanProfileMapping(t *testing.T) {
	profile, err := mapShuiyuanProfile([]byte(shuiyuanProfileFixture), "申流域", func(value string) string {
		return strings.ReplaceAll(value, "secure-uploads", "proxied")
	})
	if err != nil {
		t.Fatalf("mapping: %v", err)
	}
	if profile.Username != "申流域" || !profile.IsSelf {
		t.Fatalf("identity: %#v", profile)
	}
	if profile.ProfileViewCount != 42 || profile.BadgeCount != 3 || profile.AcceptedAnswers != 7 {
		t.Fatalf("stats: %#v", profile)
	}
	if profile.Title != "潜水员" || profile.Name != "东川路用户" {
		t.Fatalf("profile text: %#v", profile)
	}
	if !strings.Contains(profile.BioCooked, "proxied") {
		t.Fatalf("bio should be rewritten: %s", profile.BioCooked)
	}
	if len(profile.Groups) != 1 || profile.Groups[0] != "JTU" {
		t.Fatalf("groups: %#v", profile.Groups)
	}
	if len(profile.Badges) != 2 {
		t.Fatalf("badges: %#v", profile.Badges)
	}
	first := profile.Badges[0]
	if first.Name != "初级用户" || first.BadgeType != "青铜" || first.Count != 1 {
		t.Fatalf("badge mapping: %#v", first)
	}
	if strings.Contains(first.Description, "<") {
		t.Fatalf("badge description should be plain text: %q", first.Description)
	}
	encoded, err := json.Marshal(profile)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "secret@example.com") {
		t.Fatalf("private fields must not be exposed: %s", encoded)
	}

	other, err := mapShuiyuanProfile([]byte(shuiyuanProfileFixture), "别人", nil)
	if err != nil {
		t.Fatal(err)
	}
	if other.IsSelf {
		t.Fatal("other user must not be marked as self")
	}
	if other.BioCooked == "" {
		t.Fatal("nil rewrite should keep the original bio")
	}
}

const shuiyuanActivityFixture = `{
  "stream": [
    101,
    {"id": 102, "created_at": "2026-10-01T00:00:00Z", "action_type": 2, "post_number": 5, "topic_id": 7},
    {"id": 103}
  ],
  "posts": [
    {"id": 101, "topic_id": 9, "post_number": 1, "blurb": "发帖&nbsp;内容<script>x</script>", "created_at": "2026-09-30T00:00:00Z"},
    {"id": 102, "topic_id": 7, "post_number": 5, "blurb": "回复内容", "created_at": "2026-10-01T00:00:00Z"}
  ],
  "topics": [{"id": 9, "title": "新帖标题"}, {"id": 7, "title": "老帖标题"}]
}`

func TestShuiyuanActivityMapping(t *testing.T) {
	items, err := mapShuiyuanActivity([]byte(shuiyuanActivityFixture))
	if err != nil {
		t.Fatalf("mapping: %v", err)
	}
	if len(items) != 2 {
		t.Fatalf("expected the unknown id 103 to be dropped, got %#v", items)
	}
	if items[0].Kind != "topic" || items[0].TopicID != 9 || items[0].TopicTitle != "新帖标题" {
		t.Fatalf("topic item: %#v", items[0])
	}
	if items[0].Blurb != "发帖 内容 x" {
		t.Fatalf("blurb should be plain text, got %q", items[0].Blurb)
	}
	if items[1].Kind != "like" || items[1].PostNumber != 5 || items[1].TopicTitle != "老帖标题" {
		t.Fatalf("like item: %#v", items[1])
	}

	hidden, err := mapShuiyuanActivity([]byte(`{"user":{"id":1,"profile_hidden":true}}`))
	if err != nil {
		t.Fatalf("hidden profile: %v", err)
	}
	if len(hidden) != 0 {
		t.Fatalf("hidden profile should yield no items: %#v", hidden)
	}
}
