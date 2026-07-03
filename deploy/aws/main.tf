terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.0"
    }
  }
}

provider "aws" {
  region  = var.region
  profile = var.aws_profile
}

# Generate an SSH key pair so no console/CLI step is needed. The private key is
# written to deploy/aws/pp-key.pem (gitignored); the public half is registered
# with EC2 as the instance login key.
resource "tls_private_key" "pp" {
  algorithm = "ED25519"
}

resource "aws_key_pair" "pp" {
  key_name   = "${var.name}-key"
  public_key = tls_private_key.pp.public_key_openssh
}

resource "local_sensitive_file" "pem" {
  content         = tls_private_key.pp.private_key_openssh
  filename        = "${path.module}/pp-key.pem"
  file_permission = "0600"
}

# Ubuntu 24.04 LTS (noble) x86_64 — matches the glibc the binaries were built
# against (2.39), so the prebuilt indexer/relayer run without a rebuild.
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_security_group" "pp" {
  name        = "${var.name}-sg"
  description = "private-payments indexer+relayer"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_cidr]
  }
  ingress {
    description = "HTTP (nginx / certbot)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS (nginx)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "${var.name}-sg" }
}

resource "aws_instance" "pp" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = aws_key_pair.pp.key_name
  vpc_security_group_ids = [aws_security_group.pp.id]

  # Base packages only. App binaries/config are shipped via the bundle +
  # setup-server.sh after the box is up (keeps Terraform stateless of the app).
  user_data = file("${path.module}/user_data.sh")

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }
  tags = { Name = var.name }
}

resource "aws_eip" "pp" {
  instance = aws_instance.pp.id
  domain   = "vpc"
  tags     = { Name = "${var.name}-eip" }
}

output "public_ip" {
  value = aws_eip.pp.public_ip
}
output "ssh" {
  value = "ssh -i ${path.module}/pp-key.pem ubuntu@${aws_eip.pp.public_ip}"
}
output "indexer_base_url" {
  description = "Paste into android .../net/Endpoints.kt (both INDEXER_BASE and RELAYER_BASE)"
  value       = "http://${aws_eip.pp.public_ip}"
}
