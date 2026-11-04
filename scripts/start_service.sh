#!/bin/bash
set -euxo pipefail

systemctl daemon-reload
systemctl start capture-proxy-test
