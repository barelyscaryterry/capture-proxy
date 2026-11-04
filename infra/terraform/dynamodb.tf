# Partition key "requestId" (String), no sort key, no GSIs - matches
# DynamoDbAdapter.java's ATTR_REQUEST_ID usage and its GetItem/PutItem calls.
resource "aws_dynamodb_table" "capture_requests" {
  name         = var.dynamodb_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "requestId"

  attribute {
    name = "requestId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  deletion_protection_enabled = var.dynamodb_deletion_protection

  tags = {
    Name = "${local.name_prefix}-capture-requests"
  }
}
