# 詳細設計書 インデックス

practice-bank モダナイゼーション — 読み取りスライス4本の設計書一覧。

---

## ディレクトリ構成

```
doc/design/
├── README.md                        ← 本ファイル
├── azure-infra-design.md            ← Azureインフラ設計書（全スライス共通）
├── templates/
│   ├── slice-design-template.md     ← スライス詳細設計書テンプレート
│   ├── openapi-template.yaml        ← OpenAPI雛形テンプレート
│   └── golden-cases-template.md     ← ゴールデンケース定義書テンプレート
├── specs/                           ← 各スライスのAPI契約(OpenAPI)
│   ├── account-api.yaml             ← 08-account  [COBOL#1 作成]
│   ├── inquiry-api.yaml             ← 18-inquiry  [COBOL#1 作成]
│   ├── product-api.yaml             ← 05-product  [COBOL#2 作成]
│   └── customer-api.yaml            ← 03/04-customer [COBOL#1 作成]
├── slices/                          ← 各スライスの詳細設計書
│   ├── 08-account-design.md         ← [COBOL#1 + .NET#2 作成]
│   ├── 18-inquiry-design.md         ← [COBOL#1 + .NET#1 作成]
│   ├── 05-product-design.md         ← [COBOL#2 + .NET#1 作成]
│   └── 03-customer-design.md        ← [COBOL#1 + .NET#2 作成]
└── tests/                           ← ゴールデンケース定義書
    ├── 08-account-golden.md
    ├── 18-inquiry-golden.md
    ├── 05-product-golden.md
    └── 03-customer-golden.md
```

> `specs/`, `slices/`, `tests/` 配下のファイルはテンプレートをコピーして作成してください。

---

## テンプレートの使い方

1. **スライス詳細設計書**
   ```bash
   cp doc/design/templates/slice-design-template.md doc/design/slices/{N}-{name}-design.md
   ```

2. **OpenAPI雛形**
   ```bash
   cp doc/design/templates/openapi-template.yaml doc/design/specs/{name}-api.yaml
   ```

3. **ゴールデンケース定義書**
   ```bash
   cp doc/design/templates/golden-cases-template.md doc/design/tests/{N}-{name}-golden.md
   ```

---

## スライス進捗トラッカー

| スライス | 担当(仕様) | 担当(実装) | 詳細設計 | OpenAPI | ゴールデンケース | シードデータ | .NET実装 | Azureデプロイ | 疎通確認 |
|---|---|---|---|---|---|---|---|---|---|
| 18-inquiry | COBOL#1 | .NET#1 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| 08-account | COBOL#1 | .NET#2 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| 05-product | COBOL#2 | .NET#1 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| 03-customer | COBOL#1 | .NET#2 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |

---

## 受け渡しフロー

```
COBOL#1/2                        .NET#1/2                      Azure担当
────────                         ────────                       ────────
詳細設計書を確定         ──▶     ゴールデンケースで
OpenAPI雛形をコミット            ローカル一致まで実装    ──▶   ACRへpush
ゴールデンケース作成              コンテナ化
シードデータdump用意   ──▶     env/secret一覧を確定    ──▶   Container Apps更新
                                                               公開URL共有
```

---

## COBOL→HTTP ステータスコード共通ルール

全スライスで統一して適用すること。

| COBOLステータス | HTTPステータス | レスポンス `error` 値 |
|---|---|---|
| `00` | 200 OK | なし（正常レスポンス） |
| `04` | 404 Not Found | `not_found` |
| `08` | 400 Bad Request | `invalid_input` |
| `10` | 200 OK（EOF=0件） | — items=[] で返す |
| `12` | 500 Internal Server Error | `io_failure` |
| `16` | 500 Internal Server Error | `fatal` |

---

## 参照ドキュメント

- [modernization-brief.md](../work/modernization-brief.md) — 全体方針・役割分担・タイムライン
- [azure-infra-design.md](./azure-infra-design.md) — Azureインフラ設計書
- `db/migration/V1〜V7` — データベーススキーマ
- `subsystems/{N}-*/copy/api/*.cpy` — COBOLコピーブック（契約の一次情報）
