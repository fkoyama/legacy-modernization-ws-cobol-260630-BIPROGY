# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `07-feeschedule` |
| ディレクトリ | [subsystems/07-feeschedule/](../../../subsystems/07-feeschedule/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/fs-api.cpy](../../../subsystems/07-feeschedule/copy/api/fs-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

手数料区分・ティア（tier）・適用日をキーに手数料体系を参照し、適用手数料額（JPY）を返す。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 16-fee / 取引パイプライン | 手数料計算で参照 |
| 下流（呼び出し先） | 共有ログ（SHARED-LOG） | ロード処理等の構造化ログ |
| 参照データ | `feeschedule.idx`（ISAM） / `fee_schedules`（PG, TO-BE） | 一次データ |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `FEE-LOAD` | [src/fee-load.cob](../../../subsystems/07-feeschedule/src/fee-load.cob) | シードを索引へロード（重複は無視） | `MAIN-LOGIC` ほか |
| `FEE-LOOKUP-BY-TIER` | [src/fee-lookup-by-tier.cob](../../../subsystems/07-feeschedule/src/fee-lookup-by-tier.cob) | 区分+tier+適用日で手数料額（JPY）参照 | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL） ／ ロードはバッチ |
| 実行契機 | API要求ごと（LOOKUP） ／ 初期セットアップ時（LOAD） |
| 多重度・冪等性 | 参照系は冪等・並行可 |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[FEE-LOOKUP-BY-TIER]
1. 複合キー（区分+tier+適用日）で索引を参照
2. 該当なし→04
3. 手数料額（S9(9) COMP-3）を FS-OUT-FEE-JPY へ、有効終了日を FS-OUT-EFF-TO へ
```

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | 手数料区分 `FS-IN-CATEGORY` | 10/20/30/40 |
| 2 | LOAD 重複 | ロード時、重複キーは無視 |
| 3 | レコード長 | `RECORD CONTAINS 41 CHARACTERS` 固定 |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 参照に成功 |
| `04` | 該当なし | 複合キー一致レコードなし |
| `16` | 致命的エラー | I/O失敗等 |

### 2.4 排他・トランザクション制御

参照のみのため排他制御なし。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| 索引I/O失敗 | 戻り値`16`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/fs-api.cpy](../../../subsystems/07-feeschedule/copy/api/fs-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `FS-IN-CATEGORY` | `9(2)` | ✓ | 手数料区分 | 10/20/30/40 |
| `FS-IN-TIER` | `9(2)` | ✓ | ティア | 2桁 |
| `FS-IN-EFFECTIVE` | `9(8)` | ✓ | 適用日 | YYYYMMDD |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `feeschedule.idx` | 固定長41バイト | `FS-REC-KEY`（区分+tier+適用日） | 複合キー |
| 入力ファイル | [data/](../../../subsystems/07-feeschedule/data/) | 行順次 | — | LOAD 対象（重複無視） |
| テーブル | `fee_schedules` | PostgreSQL | 区分+tier+適用日 | TO-BE |

### 3.3 前提・事前条件

- `feeschedule.idx` が LOAD 済みであること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `FS-OUT-STATUS` | `9(2)` | 戻り値コード | 全ケースで設定 |
| `FS-OUT-FEE-JPY` | `S9(9)` | 手数料額（円） | 正常時。内部COMP-3から転記 |
| `FS-OUT-EFF-TO` | `9(8)` | 有効終了日 | 正常時 |

### 4.2 出力データ更新（更新系の場合）

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 索引ファイル | `feeschedule.idx` | WRITE | 全レコード | LOAD 時のみ（重複無視） |

### 4.3 後続・事後条件

- 参照した手数料額が 16-fee の手数料記帳で利用される。

---

## 5. レコード定義

レコードレイアウト: [copy/private/fd-fs.cpy](../../../subsystems/07-feeschedule/copy/private/fd-fs.cpy)（`RECORD CONTAINS 41 CHARACTERS`）

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| `FS-REC-KEY` | （複合） | 主キー | 区分+tier+適用日 |
| `FS-REC-CATEGORY` | `9(2)` | 主キー構成 | 手数料区分 |
| `FS-REC-TIER` | `9(2)` | 主キー構成 | ティア |
| `FS-REC-EFF-FROM` | `9(8)` | 主キー構成 | 適用開始日 |
| `FS-REC-TIER-MIN` | `S9(15) COMP-3` | — | ティア下限残高 |
| `FS-REC-TIER-MAX` | `S9(15) COMP-3` | — | ティア上限残高 |
| `FS-REC-AMOUNT` | `S9(9) COMP-3` | — | 手数料額 |
| `FS-REC-EFF-TO` | `9(8)` | — | 適用終了日（`FS-OUT-EFF-TO` へ転記） |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | 手数料額 | COMP-3 | NUMERIC（整数円） | 整数（円）として保持 |
| 2 | API | CALL（fs-api.cpy） | `GET /api/v1/fee-schedules/{feeCode}` | architecture.md 準拠 |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | tier の解決方法 | 残高ティアの決定ロジックを TO-BE で明確化 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*
