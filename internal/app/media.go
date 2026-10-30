package app

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type mediaKind int

const (
	mediaFile mediaKind = iota
	mediaBytes
	mediaProxy
	mediaDir
)

type mediaItem struct {
	kind        mediaKind
	path        string
	dir         string
	data        []byte
	proxyURL    string
	headers     http.Header
	client      *http.Client
	filename    string
	contentType string
	expires     time.Time
}

type MediaServer struct {
	listener net.Listener
	baseURL  string
	client   *http.Client

	mu         sync.Mutex
	items      map[string]*mediaItem
	proxyIndex map[string]string
	stop       chan struct{}
	once       sync.Once
}

func NewMediaServer() (*MediaServer, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	server := &MediaServer{
		listener:   listener,
		baseURL:    "http://" + listener.Addr().String(),
		client:     &http.Client{},
		items:      make(map[string]*mediaItem),
		proxyIndex: make(map[string]string),
		stop:       make(chan struct{}),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/r/", server.handleResource)
	mux.HandleFunc("/d/", server.handleDirectory)
	go func() {
		_ = http.Serve(listener, mux)
	}()
	go server.cleanupLoop()
	return server, nil
}

func (s *MediaServer) BaseURL() string {
	return s.baseURL
}

func (s *MediaServer) Stop() {
	s.once.Do(func() {
		close(s.stop)
		_ = s.listener.Close()
	})
}

func (s *MediaServer) cleanupLoop() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-ticker.C:
			now := time.Now()
			s.mu.Lock()
			for id, item := range s.items {
				if now.After(item.expires) {
					delete(s.items, id)
				}
			}
			for rawURL, id := range s.proxyIndex {
				if _, ok := s.items[id]; !ok {
					delete(s.proxyIndex, rawURL)
				}
			}
			s.mu.Unlock()
		}
	}
}

func randomID() string {
	buf := make([]byte, 24)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	return hex.EncodeToString(buf)
}

func (s *MediaServer) store(item *mediaItem, ttl time.Duration) string {
	id := randomID()
	item.expires = time.Now().Add(ttl)
	s.mu.Lock()
	s.items[id] = item
	s.mu.Unlock()
	return id
}

func (s *MediaServer) AddFile(filePath, filename, contentType string, ttl time.Duration) (string, error) {
	if _, err := os.Stat(filePath); err != nil {
		return "", err
	}
	id := s.store(&mediaItem{
		kind:        mediaFile,
		path:        filePath,
		filename:    filename,
		contentType: contentType,
	}, ttl)
	return s.baseURL + "/r/" + id + "/" + urlPathName(filename), nil
}

func (s *MediaServer) AddBytes(data []byte, filename, contentType string, ttl time.Duration) string {
	id := s.store(&mediaItem{
		kind:        mediaBytes,
		data:        append([]byte(nil), data...),
		filename:    filename,
		contentType: contentType,
	}, ttl)
	return s.baseURL + "/r/" + id + "/" + urlPathName(filename)
}

func (s *MediaServer) AddDir(root string, ttl time.Duration) (string, error) {
	info, err := os.Stat(root)
	if err != nil || !info.IsDir() {
		return "", fmt.Errorf("目录不可用")
	}
	id := s.store(&mediaItem{kind: mediaDir, dir: root}, ttl)
	return s.baseURL + "/d/" + id + "/", nil
}

func (s *MediaServer) AddProxy(rawURL string, headers http.Header, client *http.Client, filename, contentType string, ttl time.Duration) string {
	if client == nil {
		client = s.client
	}
	id := s.store(&mediaItem{
		kind:        mediaProxy,
		proxyURL:    rawURL,
		headers:     headers.Clone(),
		client:      client,
		filename:    filename,
		contentType: contentType,
	}, ttl)
	return s.baseURL + "/r/" + id + "/" + urlPathName(filename)
}

// AddProxyCached reuses one proxy entry per upstream URL so that a feed which
// references the same asset repeatedly does not register it over and over.
func (s *MediaServer) AddProxyCached(rawURL string, headers http.Header, client *http.Client, filename string, ttl time.Duration) string {
	if client == nil {
		client = s.client
	}
	name := urlPathName(filename)
	deadline := time.Now().Add(ttl)
	s.mu.Lock()
	if id, ok := s.proxyIndex[rawURL]; ok {
		if item, exists := s.items[id]; exists && item.kind == mediaProxy {
			item.expires = deadline
			s.mu.Unlock()
			return s.baseURL + "/r/" + id + "/" + name
		}
		delete(s.proxyIndex, rawURL)
	}
	s.mu.Unlock()

	id := s.store(&mediaItem{
		kind:     mediaProxy,
		proxyURL: rawURL,
		headers:  headers.Clone(),
		client:   client,
		filename: filename,
	}, ttl)
	s.mu.Lock()
	s.proxyIndex[rawURL] = id
	s.mu.Unlock()
	return s.baseURL + "/r/" + id + "/" + name
}

func urlPathName(name string) string {
	if name == "" {
		return "file"
	}
	name = strings.ReplaceAll(name, "/", "_")
	name = strings.ReplaceAll(name, "\\", "_")
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == ".." {
		return "file"
	}
	return name
}

func setCORS(w http.ResponseWriter) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Range, If-Range")
	w.Header().Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, Content-Disposition, Content-Type")
}

func (s *MediaServer) lookup(id string) (*mediaItem, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	item, ok := s.items[id]
	if !ok || time.Now().After(item.expires) {
		if ok {
			delete(s.items, id)
		}
		return nil, false
	}
	return item, true
}

func (s *MediaServer) handleResource(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/r/")
	id := rest
	if index := strings.IndexByte(rest, '/'); index >= 0 {
		id = rest[:index]
	}
	item, ok := s.lookup(id)
	if !ok {
		http.NotFound(w, r)
		return
	}
	if item.contentType != "" {
		w.Header().Set("Content-Type", item.contentType)
	}
	disposition := "inline"
	if r.URL.Query().Get("download") == "1" {
		disposition = "attachment"
	}
	if item.filename != "" {
		w.Header().Set("Content-Disposition", mime.FormatMediaType(disposition, map[string]string{"filename": item.filename}))
	}
	switch item.kind {
	case mediaFile:
		file, err := os.Open(item.path)
		if err != nil {
			http.Error(w, "文件不可用", http.StatusNotFound)
			return
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil {
			http.Error(w, "文件不可用", http.StatusNotFound)
			return
		}
		if item.contentType == "" {
			w.Header().Set("Content-Type", mimeForFile(item.path))
		}
		http.ServeContent(w, r, item.filename, info.ModTime(), file)
	case mediaBytes:
		http.ServeContent(w, r, item.filename, time.Time{}, bytes.NewReader(item.data))
	case mediaProxy:
		s.serveProxy(w, r, item)
	case mediaDir:
		http.NotFound(w, r)
	}
}

func (s *MediaServer) handleDirectory(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/d/")
	id := rest
	rel := ""
	if index := strings.IndexByte(rest, '/'); index >= 0 {
		id = rest[:index]
		rel = rest[index+1:]
	}
	item, ok := s.lookup(id)
	if !ok || item.kind != mediaDir {
		http.NotFound(w, r)
		return
	}
	rel = path.Clean("/" + rel)
	if rel == "/" {
		http.NotFound(w, r)
		return
	}
	rel = strings.TrimPrefix(rel, "/")
	full := filepath.Join(item.dir, filepath.FromSlash(rel))
	resolved, err := filepath.Abs(full)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	root, err := filepath.Abs(item.dir)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if resolved != root && !strings.HasPrefix(resolved, root+string(os.PathSeparator)) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	file, err := os.Open(resolved)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	if mimeType := mimeForFile(resolved); mimeType != "" {
		w.Header().Set("Content-Type", mimeType)
	}
	http.ServeContent(w, r, info.Name(), info.ModTime(), file)
}

func (s *MediaServer) serveProxy(w http.ResponseWriter, r *http.Request, item *mediaItem) {
	upstream, err := http.NewRequestWithContext(r.Context(), r.Method, item.proxyURL, nil)
	if err != nil {
		http.Error(w, "代理请求无效", http.StatusBadGateway)
		return
	}
	for key, values := range item.headers {
		for _, value := range values {
			upstream.Header.Add(key, value)
		}
	}
	for _, key := range []string{"Range", "If-Range"} {
		if value := r.Header.Get(key); value != "" {
			upstream.Header.Set(key, value)
		}
	}
	upstream.Header.Set("Accept-Encoding", "identity")
	response, err := item.client.Do(upstream)
	if err != nil {
		http.Error(w, "上游请求失败", http.StatusBadGateway)
		return
	}
	defer response.Body.Close()
	for _, key := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag"} {
		if value := response.Header.Get(key); value != "" {
			w.Header().Set(key, value)
		}
	}
	if item.contentType != "" {
		w.Header().Set("Content-Type", item.contentType)
	}
	w.WriteHeader(response.StatusCode)
	if r.Method == http.MethodHead {
		return
	}
	_, _ = io.Copy(w, response.Body)
}

func mimeForFile(fileName string) string {
	if value := mime.TypeByExtension(strings.ToLower(filepath.Ext(fileName))); value != "" {
		return value
	}
	return "application/octet-stream"
}
