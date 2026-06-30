# スライス詳細設計書

> **記入ガイド**: `{...}` の部分を埋めてください。各スライスごとに本ファイルをコピーして使います。
> 対象スライス: `18-inquiry` / `08-account` / `05-product` / `03-customer` / `04-customersearch`

---

## 基本情報

| 項目 | 内容 |
|---|---|
| スライス名 | `{サブシステム番号}-{名前}` 例: `08-account` |
| 担当 | `.NET#1` / `.NET#2` |
| 仕様担当 | `COBOL#1` / `COBOL#2` |
| 作成日 | {YYYY-MM-DD} |
| ステータス | 起草 / レビュー中 / 確定 |

---

## 1. 機能概要

### 1.1 スライスの目的

{このスライスが担う機能を1〜3文で記述。例: 口座番号をキーに口座マスタ1件を返す読み取り専用API。}

### 1.2 対応COBOLサブシステム

| COBOLファイル | 役割 |
|---|---|
| `{ファイルパス}` | {役割} |

### 1.3 スコープ

- **IN スコープ**: {実装する機能}
- **OUT スコープ**: {今回実装しない機能。例: 更新系操作、ISAM固有項目の全移植}

---

## 2. COBOL→API 契約マッピング

### 2.1 COBOLステータスコード → HTTPステータス対応

| COBOLステータス | 意味 | HTTPステータス | レスポンスボディ |
|---|---|---|---|
| `00` | 正常 | 200 OK | 正常レスポンスJSON |
| `04` | 該当なし | 404 Not Found | `{"error":"not_found"}` |
| `08` | 入力不正 | 400 Bad Request | `{"error":"invalid_input","detail":"{理由}"}` |
| `12` | I/O失敗 | 500 Internal Server Error | `{"error":"io_failure"}` |
| `16` | 致命的エラー | 500 Internal Server Error | `{"error":"fatal"}` |

> 追加ステータスがある場合は行を追加してください。

### 2.2 入力フィールドマッピング

| COBOLフィールド名 | PIC | HTTPパラメータ | 型 | バリデーション |
|---|---|---|---|---|
| `{COBOL-FIELD}` | `{PIC定義}` | `{パスパラメータ/クエリ名}` | string/integer | {制約。例: 13桁数字のみ} |

### 2.3 出力フィールドマッピング

| COBOLフィールド名 | PIC | JSONキー | 型 | 備考 |
|---|---|---|---|---|
| `{COBOL-FIELD}` | `{PIC定義}` | `{camelCase名}` | string/integer/date | {変換ルール。例: YYYYMMDD→ISO8601} |

> **差異注記**: ISAMマスタにあってPostgreSQLに存在しない項目は返却しない。
> 例: `ACCT-LO-OVERDRAFT-LIMIT`、`ACCT-LO-TERM-DAYS` はISAM固有のため今回スコープ外。

---

## 3. データソース

### 3.1 対象テーブル/ビュー

| テーブル名 | 用途 | 主キー |
|---|---|---|
| `{table_name}` | {用途} | `{pk_column}` |

### 3.2 主要クエリ

```sql
-- {クエリ用途の説明}
SELECT {列リスト}
FROM   {テーブル名}
WHERE  {条件};
```

### 3.3 インデックス利用確認

| クエリパターン | 使用インデックス | 備考 |
|---|---|---|
| `{WHERE句}` | `{インデックス名}` | {あれば補足} |

---

## 4. APIエンドポイント定義

### 4.1 エンドポイント一覧

| メソッド | パス | 説明 |
|---|---|---|
| GET | `{/resource/{id}}` | {説明} |

### 4.2 リクエスト詳細

#### `GET {/resource/{id}}`

**パスパラメータ**

| 名前 | 型 | 必須 | 説明 | バリデーション |
|---|---|---|---|---|
| `{id}` | string | ✓ | {説明} | {制約} |

**クエリパラメータ** *(ある場合のみ)*

| 名前 | 型 | 必須 | 説明 | デフォルト |
|---|---|---|---|---|
| `{param}` | string | | {説明} | {デフォルト値} |

### 4.3 レスポンス詳細

**200 OK**

```json
{
  "{camelCaseField}": "{型と例}"
}
```

**400 Bad Request**

```json
{ "error": "invalid_input", "detail": "{バリデーション失敗理由}" }
```

**404 Not Found**

```json
{ "error": "not_found" }
```

**500 Internal Server Error**

```json
{ "error": "io_failure" }
```

---

## 5. 実装方針（.NET）

### 5.1 プロジェクト構成

```
{SliceName}.Api/
  Program.cs          ← エントリポイント・DI登録・ルーティング
  Endpoints/
    {Resource}Endpoints.cs   ← MapGet定義
  Models/
    {Resource}.cs            ← レスポンスDTO
  Repositories/
    {Resource}Repository.cs  ← Npgsqlクエリ実装
```

### 5.2 バリデーション実装方針

```csharp
// 例: 13桁数字チェック
if ({param}.Length != {N} || !{param}.All(char.IsDigit))
    return Results.BadRequest(new { error = "invalid_input", detail = "..." });
```

### 5.3 接続文字列・シークレット管理

| 項目 | 取得元 | Key Vault シークレット名 |
|---|---|---|
| DB接続文字列 | Key Vault | `{secret-name}` |
| {その他} | {取得元} | `{secret-name}` |

---

## 6. 非機能要件

| 項目 | 要件 |
|---|---|
| レスポンスタイム目標 | {例: p99 ≤ 500ms} |
| 認証 | {例: なし（内部API）/ Azure AD} |
| ログ | App Insights トレース（リクエスト/エラー） |
| ヘルスチェック | `GET /healthz` → 200 |

---

## 7. 受け渡し物チェックリスト

### COBOL#1/2 → .NET

- [ ] 仕様メモ（本ドキュメント）確定
- [ ] OpenAPI雛形 (`docs/specs/{slice}-api.yaml`) コミット済み
- [ ] ゴールデンケース (`docs/tests/{slice}-golden.md`) コミット済み
- [ ] サンプルデータdump (`data/seed/{slice}-seed.sql`) 用意済み

### .NET → Azure

- [ ] コンテナイメージ (ACR タグ) 確定
- [ ] 必要な env/secret 一覧 (`docs/deploy/{slice}-env.md`) 確定

---

## 8. 未解決事項・差異メモ

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | {ISAM固有項目の扱い} | {今回はPGにある項目のみ返却} | {担当} | {日時} |

---

*テンプレートバージョン: 1.0 / 参照: doc/work/modernization-brief.md § 5, 7*
