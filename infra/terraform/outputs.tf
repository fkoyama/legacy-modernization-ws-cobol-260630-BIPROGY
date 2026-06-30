output "resource_group_name" {
  description = "作成された Resource Group 名"
  value       = azurerm_resource_group.main.name
}

# ---- ACR ----
output "acr_login_server" {
  description = "ACR ログインサーバー（docker login / push 先）"
  value       = azurerm_container_registry.main.login_server
}

output "acr_admin_username" {
  description = "ACR admin ユーザー名"
  value       = azurerm_container_registry.main.admin_username
}

output "acr_admin_password" {
  description = "ACR admin パスワード"
  value       = azurerm_container_registry.main.admin_password
  sensitive   = true
}

# ---- Container App ----
output "container_app_fqdn" {
  description = "Container App の公開 FQDN"
  value       = azurerm_container_app.api.latest_revision_fqdn
}

output "container_app_url" {
  description = "Container App の公開 URL"
  value       = "https://${azurerm_container_app.api.latest_revision_fqdn}"
}

# ---- PostgreSQL ----
output "postgres_fqdn" {
  description = "PostgreSQL Flexible Server の FQDN"
  value       = azurerm_postgresql_flexible_server.main.fqdn
}

output "postgres_database" {
  description = "アプリ用データベース名"
  value       = azurerm_postgresql_flexible_server_database.app.name
}

output "postgres_admin_user" {
  description = "PostgreSQL 管理者ユーザー名"
  value       = azurerm_postgresql_flexible_server.main.administrator_login
}

output "psql_connection_command" {
  description = "psql 接続コマンド例（パスワードは変数値）"
  value       = "psql \"host=${azurerm_postgresql_flexible_server.main.fqdn} port=5432 dbname=${azurerm_postgresql_flexible_server_database.app.name} user=${var.pg_admin_user} sslmode=require\""
}

# ---- Application Insights ----
output "app_insights_connection_string" {
  description = "Application Insights 接続文字列"
  value       = azurerm_application_insights.main.connection_string
  sensitive   = true
}
