"""サンプル口座 API（FastAPI）

PostgreSQL に sample_accounts テーブル（口座番号・口座名(全角カナ)）を作成し、
サンプルデータ3件を投入して参照する最小サンプル。
接続情報は Container App に注入された環境変数 (PGHOST 等) を使用する。
"""
import os

import psycopg2
from fastapi import FastAPI, HTTPException

app = FastAPI(title="Sample Accounts API", version="1.0.0")

# サンプルデータ: (口座番号, 口座名(全角カナ))
SAMPLE_ACCOUNTS = [
    ("1001", "サトウ タロウ"),
    ("1002", "スズキ ハナコ"),
    ("1003", "タカハシ ジロウ"),
]


def get_conn():
    return psycopg2.connect(
        host=os.environ.get("PGHOST", "localhost"),
        port=os.environ.get("PGPORT", "5432"),
        user=os.environ.get("PGUSER", "pgadmin"),
        password=os.environ.get("PGPASSWORD", ""),
        dbname=os.environ.get("PGDATABASE", "banking"),
        sslmode=os.environ.get("PGSSLMODE", "require"),
        connect_timeout=10,
    )


def init_db():
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS sample_accounts (
                    account_no   VARCHAR(20) PRIMARY KEY,
                    account_kana TEXT NOT NULL
                )
                """
            )
            cur.execute("SELECT COUNT(*) FROM sample_accounts")
            if cur.fetchone()[0] == 0:
                cur.executemany(
                    "INSERT INTO sample_accounts (account_no, account_kana) "
                    "VALUES (%s, %s)",
                    SAMPLE_ACCOUNTS,
                )
        conn.commit()
    finally:
        conn.close()


@app.on_event("startup")
def on_startup():
    try:
        init_db()
    except Exception as exc:  # 起動時にDB未接続でもアプリ自体は起動させる
        print(f"[startup] DB init skipped: {exc}")


@app.get("/")
def root():
    return {"service": "sample-accounts", "status": "ok!!!"}


@app.get("/health")
def health():
    return {"status": "healthy"}


@app.get("/accounts")
def list_accounts():
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT account_no, account_kana FROM sample_accounts "
                "ORDER BY account_no"
            )
            rows = cur.fetchall()
    finally:
        conn.close()
    return [{"account_no": r[0], "account_kana": r[1]} for r in rows]


@app.get("/accounts/{account_no}")
def get_account(account_no: str):
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT account_no, account_kana FROM sample_accounts "
                "WHERE account_no = %s",
                (account_no,),
            )
            row = cur.fetchone()
    finally:
        conn.close()
    if row is None:
        raise HTTPException(status_code=404, detail="account not found")
    return {"account_no": row[0], "account_kana": row[1]}
