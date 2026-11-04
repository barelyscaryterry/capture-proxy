resource "aws_security_group" "ec2" {
  name        = "${local.name_prefix}-ec2-sg"
  description = "Security group for the ${var.service_name} app host"
  vpc_id      = aws_vpc.this.id

  # No port 22 rule - access is via SSM Session Manager only.

  dynamic "ingress" {
    for_each = var.app_ingress_cidrs
    content {
      description = "Actuator/health traffic on ${var.app_port}"
      from_port   = var.app_port
      to_port     = var.app_port
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
    }
  }

  dynamic "ingress" {
    for_each = var.app_ingress_cidrs
    content {
      description = "Netty capture traffic on ${var.netty_port}"
      from_port   = var.netty_port
      to_port     = var.netty_port
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
    }
  }

  # TODO(test-service-peering): once VPC peering with test-service (or a
  # shared VPC) exists, narrow app_ingress_cidrs (or switch to a security
  # group reference) so only test-service's traffic can reach netty_port,
  # rather than the whole capture-proxy VPC. netty_port is also where the
  # planned NLB + host-terminated TLS work (see project CLAUDE.md) will
  # eventually attach - that will replace this VPC-internal-only rule with
  # NLB-facing ingress.

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name_prefix}-ec2-sg"
  }
}
