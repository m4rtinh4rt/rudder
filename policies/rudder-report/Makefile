# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2020 Normation SAS

include ../../rust.makefile

test:
	cargo test --locked --package rudder-report

static:
	cargo auditable build --release --locked

check: lint test

.PHONY: clean
