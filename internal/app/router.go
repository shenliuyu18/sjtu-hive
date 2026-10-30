package app

import (
	"encoding/json"
	"fmt"
	"sync"
)

type Handler func(args []json.RawMessage) (any, error)

type Router struct {
	mu       sync.RWMutex
	handlers map[string]Handler
}

func NewRouter() *Router {
	return &Router{handlers: make(map[string]Handler)}
}

func (r *Router) Handle(channel string, handler Handler) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.handlers[channel]; exists {
		panic("duplicate channel handler: " + channel)
	}
	r.handlers[channel] = handler
}

func (r *Router) Dispatch(channel string, args []json.RawMessage) (any, error) {
	r.mu.RLock()
	handler, ok := r.handlers[channel]
	r.mu.RUnlock()
	if !ok {
		return nil, fmt.Errorf("未知操作：%s", channel)
	}
	return handler(args)
}

func DecodeArg[T any](args []json.RawMessage, index int) (T, error) {
	var zero T
	if index >= len(args) {
		return zero, fmt.Errorf("缺少参数")
	}
	var value T
	if err := json.Unmarshal(args[index], &value); err != nil {
		return zero, fmt.Errorf("参数格式错误：%w", err)
	}
	return value, nil
}

func DecodeOptionalArg[T any](args []json.RawMessage, index int) (T, bool, error) {
	var zero T
	if index >= len(args) || len(args[index]) == 0 || string(args[index]) == "null" {
		return zero, false, nil
	}
	var value T
	if err := json.Unmarshal(args[index], &value); err != nil {
		return zero, false, fmt.Errorf("参数格式错误：%w", err)
	}
	return value, true, nil
}
