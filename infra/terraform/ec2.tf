data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_instance" "app" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.ec2_instance_type
  subnet_id              = values(aws_subnet.public)[0].id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  associate_public_ip_address = true

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required" # enforce IMDSv2
  }

  # Installs Java 17 + the CodeDeploy agent, and provisions the systemd
  # unit once at launch. CodeDeploy (see codedeploy.tf) only ever replaces
  # the jar under /opt/<service>/app.jar and restarts the service - the
  # unit file itself isn't part of the per-deploy bundle.
  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    service_name      = local.name_prefix
    aws_region        = var.aws_region
    capture_log_group = aws_cloudwatch_log_group.captures.name
  })
  user_data_replace_on_change = true

  tags = {
    Name = "${local.name_prefix}-app"
  }
}
