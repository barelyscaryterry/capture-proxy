output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = [for s in aws_subnet.public : s.id]
}

output "dynamodb_table_name" {
  value = aws_dynamodb_table.capture_requests.name
}

output "dynamodb_table_arn" {
  value = aws_dynamodb_table.capture_requests.arn
}

output "ec2_instance_id" {
  value = aws_instance.app.id
}

output "ec2_public_ip" {
  value = aws_instance.app.public_ip
}

# Called out for future manual wiring into test-service's Terraform (SG
# reference or peering) - see the TODOs in vpc.tf and security_groups.tf.
output "ec2_security_group_id" {
  value = aws_security_group.ec2.id
}

output "ec2_iam_role_arn" {
  value = aws_iam_role.ec2.arn
}

output "codedeploy_app_name" {
  value = aws_codedeploy_app.this.name
}

output "codedeploy_deployment_group_name" {
  value = aws_codedeploy_deployment_group.this.deployment_group_name
}

output "deploy_artifacts_bucket_name" {
  value = aws_s3_bucket.deploy_artifacts.id
}

output "cloudwatch_dashboard_url" {
  value = "https://${var.aws_region}.console.aws.amazon.com/cloudwatch/home?region=${var.aws_region}#dashboards:name=${aws_cloudwatch_dashboard.this.dashboard_name}"
}
