# サブシステム設計書

---

## 基本情報

| 項目 | 内容 |
|---|---|
| サブシステム名 | `01-calendar` |
| ディレクトリ | [subsystems/01-calendar/](../../../subsystems/01-calendar/) |
| 分類 | マスタ参照系 |
| API契約 | [copy/api/cal-api.cpy](../../../subsystems/01-calendar/copy/api/cal-api.cpy) |
| 作成日 | 2026-06-30 |
| ステータス | 起草 |

---

## 1. 処理概要

### 1.1 目的

営業日カレンダーを参照し、指定日が営業日／祝日／週末のいずれかを判定する。あわせて翌営業日・前営業日を算出する。日本の祝日を含む 2026〜2030 年（範囲 20260101–20301231）を対象とする。

### 1.2 位置づけ・依存関係

| 区分 | 対象 | 内容 |
|---|---|---|
| 上流（呼び出し元） | 09-accountlifecycle / 取引パイプライン各機能 | 休眠日計算・営業日判定で参照 |
| 下流（呼び出し先） | 共有ログ（SHARED-LOG） | ロード処理等の構造化ログ |
| 参照データ | `calendar.idx`（ISAM） / `calendar`（PG, TO-BE） | 一次データ |

### 1.3 構成プログラム

| Program-ID | ファイル | 機能 | 主要PARAGRAPH |
|---|---|---|---|
| `CAL-LOAD` | [src/cal-load.cob](../../../subsystems/01-calendar/src/cal-load.cob) | シードを索引へロード | `MAIN-LOGIC`, `WRITE-CAL-RECORD`, `LOG-COMPLETE` |
| `CAL-LOOKUP` | [src/cal-lookup.cob](../../../subsystems/01-calendar/src/cal-lookup.cob) | 日付で区分照会（メモリキャッシュ最大1826件=5年） | `MAIN-LOGIC`, `LOAD-CACHE` |
| `CAL-NEXT-BD` | [src/cal-next-bd.cob](../../../subsystems/01-calendar/src/cal-next-bd.cob) | 翌営業日（最大10日先まで探索） | `MAIN-LOGIC` |
| `CAL-PREV-BD` | [src/cal-prev-bd.cob](../../../subsystems/01-calendar/src/cal-prev-bd.cob) | 前営業日 | `MAIN-LOGIC` |

### 1.4 起動方式

| 項目 | 内容 |
|---|---|
| 起動形態 | オンライン（CALL） ／ ロードはバッチ |
| 実行契機 | API要求ごと（LOOKUP/NEXT-BD/PREV-BD） ／ 初期セットアップ時（LOAD） |
| 多重度・冪等性 | 参照系は冪等・並行可。LOOKUP はメモリキャッシュにより同一プロセス内で再ロード不要 |

---

## 2. 処理詳細

### 2.1 処理フロー

```
[CAL-LOOKUP]
1. 入力日付 CAL-INPUT-DATE の範囲検証（20260101–20301231）
2. キャッシュ未ロードなら LOAD-CACHE で索引を全件読込（最大1826件）
3. キャッシュ上で日付を検索
4. 該当区分（B/H/W）と祝日名を出力エリアへセット
5. 該当なし→04 / 範囲外→08 / キャッシュ失敗→12

[CAL-NEXT-BD]
1. 起点日から最大10日先まで1日ずつ前進
2. 各日付を区分判定し、最初の営業日(B)を CAL-OUTPUT-NEXT-DATE に設定
```

### 2.2 主要ロジック・業務ルール

| # | ルール/分岐 | 内容 |
|---|---|---|
| 1 | 日付範囲 | 20260101–20301231 の範囲外は `08`（日付不正） |
| 2 | キャッシュ | LOOKUP は最大1826件（5年分）をメモリ保持し再ロードを回避 |
| 3 | 営業日探索 | NEXT-BD/PREV-BD は最大10日まで探索し、超過時は該当なし扱い |

### 2.3 戻り値コード

| コード | 意味 | 発生条件 |
|---|---|---|
| `00` | 正常 | 区分判定・営業日算出に成功 |
| `04` | 該当なし | 索引に対象日付レコードが存在しない |
| `08` | 入力不正 | 日付が範囲外・形式不正 |
| `12` | I/O失敗 | キャッシュロード失敗 |
| `16` | 致命的エラー | 想定外の異常 |

### 2.4 排他・トランザクション制御

参照のみのため排他制御なし。LOAD は索引の再作成（全件 WRITE）。

### 2.5 エラー処理・ログ

| 事象 | 処理 | ログ出力 |
|---|---|---|
| キャッシュロード失敗 | 戻り値`12`でリターン | [shared/copy/shared-log-api.cpy](../../../shared/copy/shared-log-api.cpy) 経由 |
| 日付範囲外 | 戻り値`08`でリターン | — |

---

## 3. 入力インターフェース

### 3.1 入力パラメータ（呼び出し時）

API契約: [copy/api/cal-api.cpy](../../../subsystems/01-calendar/copy/api/cal-api.cpy)

| COBOLフィールド名 | PIC | 必須 | 説明 | 制約・取り得る値 |
|---|---|---|---|---|
| `CAL-INPUT-DATE` | `9(8)` | ✓ | 判定対象日（YYYYMMDD） | 20260101–20301231 |

### 3.2 入力データソース

| 種別 | 名称 | 形式 | キー | 備考 |
|---|---|---|---|---|
| 索引ファイル | `calendar.idx` | 固定長60バイト | 日付8桁 | LOOKUP の一次データ |
| 入力ファイル | [data/calendar-seed.dat](../../../subsystems/01-calendar/data/) | 行順次 | — | LOAD 対象。日本の祝日含む |
| テーブル | `calendar` | PostgreSQL | 日付(8) | TO-BE |

### 3.3 前提・事前条件

- `calendar.idx` が LOAD 済みであること。
- 入力日付がカレンダー対象範囲内であること。

---

## 4. 出力インターフェース

### 4.1 出力パラメータ（リターン時）

| COBOLフィールド名 | PIC | 説明 | 設定条件・変換ルール |
|---|---|---|---|
| `CAL-STATUS` | `9(2)` | 戻り値コード | 全ケースで設定 |
| `CAL-OUTPUT-DAY-TYPE` | `X(1)` | 区分 | `B`=営業/`H`=祝日/`W`=週末 |
| `CAL-OUTPUT-HOLIDAY-NAME` | `X(40)` | 祝日名 | 祝日時のみ |
| `CAL-OUTPUT-NEXT-DATE` | `9(8)` | 翌/前営業日 | NEXT-BD/PREV-BD で設定 |

### 4.2 出力データ更新（更新系の場合）

| 種別 | 名称 | 操作 | 対象項目 | 備考 |
|---|---|---|---|---|
| 索引ファイル | `calendar.idx` | WRITE | 全レコード | LOAD 時のみ |

### 4.3 後続・事後条件

- 営業日判定結果が上流の休眠日計算・取引可否判定に利用される。

---

## 5. レコード定義

レコードレイアウト: [copy/private/](../../../subsystems/01-calendar/copy/private/)（calendar.idx は60バイト固定）

| フィールド名 | PIC | キー区分 | 説明 |
|---|---|---|---|
| 日付 | `9(8)` | 主キー | YYYYMMDD |
| 区分 | `X(1)` | — | B/H/W |
| 祝日名 | `X(40)` | — | 祝日時のみ有効 |

---

## 6. モダナイゼーション差異メモ

| # | 項目 | AS-IS（COBOL/ISAM） | TO-BE（Java/PostgreSQL） | 対応方針 |
|---|---|---|---|---|
| 1 | キャッシュ | プロセス内メモリ1826件 | アプリ内キャッシュ or DB直参照 | Master Reference App 側で実装 |
| 2 | API | CALL（cal-api.cpy） | `GET /api/v1/business-calendar/{date}` | architecture.md 準拠 |

---

## 7. 未解決事項

| # | 項目 | 対応方針 | 担当 | 期限 |
|---|---|---|---|---|
| 1 | 祝日データ更新運用 | カレンダー範囲拡張時の再ロード手順を定義 | TBD | TBD |

---

*テンプレートバージョン: 1.0 / 参照: doc/design/specs-asis/01-master-reference.md, doc/design/specs-tobe/architecutre.md*
