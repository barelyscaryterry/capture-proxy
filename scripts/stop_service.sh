#!/bin/bash
set -euxo pipefail

systemctl stop capture-proxy-test || true
