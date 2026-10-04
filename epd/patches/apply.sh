#!/bin/bash
# Copyright (C) 2026 The hk-hisense-a6l-eink authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Applies the framework patches of the clean-room E-ink stack to a
# LineageOS 18.1 source tree.
#
#   apply.sh <android-source-root> [--check]
#
# --check only verifies that the patches apply (git apply --check).
# Without it the patches are applied as commits with git am.

set -euo pipefail

usage() {
    echo "usage: $0 <android-source-root> [--check]" >&2
    exit 2
}

[ $# -ge 1 ] || usage
TOP=$1
MODE=${2:-}
HERE=$(cd "$(dirname "$0")" && pwd)

# patch directory -> project path in the source tree
declare -A PROJECTS=(
    [frameworks_base]=frameworks/base
)

for dir in "${!PROJECTS[@]}"; do
    project="$TOP/${PROJECTS[$dir]}"
    [ -d "$project" ] || { echo "missing project: $project" >&2; exit 1; }
    patches=("$HERE/$dir"/*.patch)
    if [ "$MODE" = "--check" ]; then
        git -C "$project" apply --check "${patches[@]}"
        echo "OK: ${#patches[@]} patch(es) apply to ${PROJECTS[$dir]}"
    elif [ -z "$MODE" ]; then
        git -C "$project" am --3way "${patches[@]}"
    else
        usage
    fi
done
