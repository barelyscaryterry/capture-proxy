# Dashboard for the app metrics published by Micrometer (see
# management.cloudwatch.metrics.export.* in application.properties) plus the
# DynamoDB/EC2 metrics AWS already emits for free. SEARCH expressions are used
# instead of hardcoded dimensions because Micrometer's CloudWatch registry tags
# each series (uri/method/outcome/status) and those dimension values aren't
# known statically here.
#
# Note: the HTTP request widgets below only cover Tomcat/actuator traffic
# (:8080) - Spring MVC's Micrometer auto-instrumentation doesn't see the
# Netty capture path (:8443), which isn't backed by a DispatcherServlet.
# There's no request-rate/latency metric for the actual capture path yet.
resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = local.name_prefix

  dashboard_body = jsonencode({
    widgets = [
      {
        type   = "text"
        x      = 0
        y      = 0
        width  = 24
        height = 1
        properties = {
          markdown = "# ${local.name_prefix}"
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 1
        width  = 12
        height = 6
        properties = {
          title   = "Actuator HTTP request rate (:8080 only)"
          region  = var.aws_region
          view    = "timeSeries"
          stacked = false
          metrics = [
            [{ expression = "SEARCH('{${var.cloudwatch_namespace},method,outcome,status,uri} MetricName=\"http.server.requests.count\"', 'Sum', 60)", label = "" }]
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 1
        width  = 12
        height = 6
        properties = {
          title   = "Actuator HTTP request latency p99 (:8080 only)"
          region  = var.aws_region
          view    = "timeSeries"
          stacked = false
          metrics = [
            [{ expression = "SEARCH('{${var.cloudwatch_namespace},method,outcome,status,uri} MetricName=\"http.server.requests.max\"', 'p99', 60)", label = "" }]
          ]
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 7
        width  = 12
        height = 6
        properties = {
          title   = "DynamoDB - capture-proxy-requests table"
          region  = var.aws_region
          view    = "timeSeries"
          stacked = false
          metrics = [
            ["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", aws_dynamodb_table.capture_requests.name, { stat = "Sum" }],
            ["AWS/DynamoDB", "ConsumedWriteCapacityUnits", "TableName", aws_dynamodb_table.capture_requests.name, { stat = "Sum" }],
            ["AWS/DynamoDB", "ThrottledRequests", "TableName", aws_dynamodb_table.capture_requests.name, { stat = "Sum" }],
            ["AWS/DynamoDB", "SystemErrors", "TableName", aws_dynamodb_table.capture_requests.name, { stat = "Sum" }],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 7
        width  = 12
        height = 6
        properties = {
          title   = "EC2 - app host"
          region  = var.aws_region
          view    = "timeSeries"
          stacked = false
          metrics = [
            ["AWS/EC2", "CPUUtilization", "InstanceId", aws_instance.app.id, { stat = "Average" }],
            ["AWS/EC2", "StatusCheckFailed", "InstanceId", aws_instance.app.id, { stat = "Maximum" }],
          ]
        }
      },
    ]
  })
}

# Captured proxy exchanges: one JSON object per line, written by LogFileCaptureSink to
# /var/log/capture-proxy/captures.log and shipped here by the CloudWatch agent (see user_data).
# Short retention is deliberate - captures are disposable, and this caps storage cost.
resource "aws_cloudwatch_log_group" "captures" {
  name              = "/${var.service_name}/${var.environment}/captures"
  retention_in_days = var.capture_log_retention_days
}
