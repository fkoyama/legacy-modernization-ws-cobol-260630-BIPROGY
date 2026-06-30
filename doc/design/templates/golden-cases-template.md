# ゴールデンケース定義書

> **記入ガイド**: `{...}` を埋めてください。各スライスごとに本ファイルをコピーします。
> **目的**: COBOL実機の入出力仕様を「入力 → 期待レスポンス」の形で固定し、.NET実装の受け入れ基準とする。

---

## 基本情報

| 項目 | 内容 |
|---|---|
| スライス | `{08-account}` |
| 担当 | `COBOL#2` |
| 作成日 | {YYYY-MM-DD} |
| 確認方法 | `curl` / 統合テストスクリプト `tests/e2e/{slice}-golden.sh` |

---

## 1. データ準備

### 1.1 前提シードデータ

ゴールデンケースが依存するレコードを以下に列挙する。
`data/seed/{slice}-seed.sql` にINSERT文として用意すること。

```sql
-- {説明。例: 口座照会用テストデータ}
INSERT INTO {table_name} ({columns})
VALUES
  ({正常ケース用データ行1}),
  ({正常ケース用データ行2}),
  ...;
```

### 1.2 テストデータの境界値一覧

| ID | 値 | 用途 |
|---|---|---|
| `{存在するID}` | `{例: 0000000012345}` | 正常取得ケース |
| `{存在しないID}` | `{例: 9999999999999}` | 404ケース |
| `{不正形式}` | `{例: 00ABC}` | 400ケース |

---

## 2. ゴールデンケース一覧

### GC-01: 正常取得（存在するレコード）

| 項目 | 内容 |
|---|---|
| **ケースID** | GC-01 |
| **目的** | {説明。例: 有効な口座番号で200が返ること} |
| **COBOLステータス** | `00` |
| **期待HTTPステータス** | `200 OK` |

**リクエスト**

```http
GET /{resource}/{存在するID}
```

または curl:

```bash
curl -s "http://localhost:5000/{resource}/{存在するID}"
```

**期待レスポンスボディ**

```json
{
  "{camelCaseField1}": "{期待値}",
  "{camelCaseField2}": "{期待値}",
  "{camelCaseField3}": "{期待値}"
}
```

**検証ポイント**

- [ ] HTTPステータス = 200
- [ ] `Content-Type: application/json`
- [ ] `{camelCaseField1}` = `"{期待値}"`
- [ ] 日付フィールドが ISO 8601 形式 (`YYYY-MM-DD`) であること
- [ ] 数値フィールドが文字列でなく数値型で返ること（仕様による）

---

### GC-02: 該当なし（存在しないID）

| 項目 | 内容 |
|---|---|
| **ケースID** | GC-02 |
| **目的** | {説明。例: 存在しない口座番号で404が返ること} |
| **COBOLステータス** | `04` |
| **期待HTTPステータス** | `404 Not Found` |

**リクエスト**

```bash
curl -s "http://localhost:5000/{resource}/{存在しないID}"
```

**期待レスポンスボディ**

```json
{ "error": "not_found" }
```

**検証ポイント**

- [ ] HTTPステータス = 404
- [ ] ボディに `"error": "not_found"` が含まれること

---

### GC-03: 入力不正（バリデーションエラー）

| 項目 | 内容 |
|---|---|
| **ケースID** | GC-03 |
| **目的** | {説明。例: 13桁以外の口座番号で400が返ること} |
| **COBOLステータス** | `08` |
| **期待HTTPステータス** | `400 Bad Request` |

**リクエスト（バリデーションケースを複数列挙）**

```bash
# 桁数不足
curl -s "http://localhost:5000/{resource}/123"

# 数字以外を含む
curl -s "http://localhost:5000/{resource}/AAAA000000000"

# 空文字
curl -s "http://localhost:5000/{resource}/"
```

**期待レスポンスボディ**

```json
{ "error": "invalid_input", "detail": "{バリデーション失敗理由}" }
```

**検証ポイント**

- [ ] HTTPステータス = 400
- [ ] `"error": "invalid_input"` が含まれること
- [ ] `"detail"` フィールドが存在すること

---

### GC-04: ステータス別レスポンス確認 *(ステータス区分があるスライスのみ)*

| ケースID | 対象レコードのステータス | 期待レスポンス内 `status` 値 |
|---|---|---|
| GC-04-A | 申込中 (`P`) | `"P"` |
| GC-04-B | 有効 (`A`) | `"A"` |
| GC-04-C | 休眠 (`D`) | `"D"` |
| GC-04-D | 停止 (`S`) | `"S"` |
| GC-04-E | 解約 (`C`) | `"C"` |
| GC-04-F | 再活性中 (`R`) | `"R"` |

> 口座照会(08)向け。他スライスでは不要なら削除可。

---

### GC-05: ページング・リスト *(検索エンドポイントがある場合のみ)*

| 項目 | 内容 |
|---|---|
| **ケースID** | GC-05 |
| **目的** | {説明。例: カナ前方一致検索で正しく絞り込まれること} |
| **COBOLステータス** | `00` |
| **期待HTTPステータス** | `200 OK` |

**リクエスト**

```bash
curl -s "http://localhost:5000/{resource}?{queryParam}={検索値}&pageSize=2"
```

**期待レスポンスボディ（件数・ページング）**

```json
{
  "items": [
    { "{camelCaseField}": "{期待値1}" },
    { "{camelCaseField}": "{期待値2}" }
  ],
  "hasMore": true,
  "lastId": "{カーソル値}"
}
```

**検証ポイント**

- [ ] `items` の件数 = `pageSize` 以下
- [ ] `hasMore` が正しく設定されること
- [ ] `lastId` を `startAfter` に渡すと次ページが取れること

---

## 3. 自動テストスクリプト骨子

```bash
#!/usr/bin/env bash
# tests/e2e/{slice}-golden.sh
# 実行: BASE_URL=http://localhost:5000 bash tests/e2e/{slice}-golden.sh

set -euo pipefail
BASE="${BASE_URL:-http://localhost:5000}"
PASS=0; FAIL=0

check() {
  local id="$1" desc="$2" expected_status="$3" url="$4" expected_body="$5"
  actual=$(curl -s -o /tmp/body.json -w "%{http_code}" "$url")
  if [[ "$actual" == "$expected_status" ]] && echo "$(cat /tmp/body.json)" | grep -q "$expected_body"; then
    echo "PASS [$id] $desc"; ((PASS++))
  else
    echo "FAIL [$id] $desc — HTTP=$actual body=$(cat /tmp/body.json)"; ((FAIL++))
  fi
}

# GC-01: 正常取得
check GC-01 "正常取得" "200" "$BASE/{resource}/{存在するID}" '"{camelCaseField}"'

# GC-02: 該当なし
check GC-02 "該当なし" "404" "$BASE/{resource}/{存在しないID}" 'not_found'

# GC-03: バリデーションエラー（桁数不足）
check GC-03 "バリデーションエラー" "400" "$BASE/{resource}/123" 'invalid_input'

echo ""
echo "Results: PASS=$PASS FAIL=$FAIL"
[[ $FAIL -eq 0 ]]
```

---

## 4. ゴールデンケース進捗

| ケースID | 作成済み | .NET実装で一致確認 | 備考 |
|---|---|---|---|
| GC-01 | [ ] | [ ] | |
| GC-02 | [ ] | [ ] | |
| GC-03 | [ ] | [ ] | |
| GC-04 | [ ] | [ ] | ステータス別 |
| GC-05 | [ ] | [ ] | 検索系のみ |

---

*テンプレートバージョン: 1.0 / 参照: doc/work/modernization-brief.md § 7, 9*
