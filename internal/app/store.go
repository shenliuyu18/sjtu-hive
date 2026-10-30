package app

import (
	"context"
	"database/sql"
	_ "embed"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaSQL string

func OpenStore(path string) (*sql.DB, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(ON)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if err := db.Ping(); err != nil {
		_ = db.Close()
		return nil, err
	}
	if _, err := db.Exec(schemaSQL); err != nil {
		_ = db.Close()
		return nil, err
	}
	if _, err := db.Exec(`PRAGMA user_version = 1`); err != nil {
		_ = db.Close()
		return nil, err
	}
	return db, nil
}

func Now() string {
	return time.Now().Format(time.RFC3339)
}

func Tx(ctx context.Context, db *sql.DB, fn func(*sql.Tx) error) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		_ = tx.Rollback()
		return err
	}
	return tx.Commit()
}

func BoolInt(value bool) int {
	if value {
		return 1
	}
	return 0
}

func (a *App) VacuumInto(path string) error {
	_, err := a.db.Exec(`VACUUM INTO ?`, path)
	return err
}
