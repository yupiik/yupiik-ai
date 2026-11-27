#!/bin/sh
# Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

printf '%s' "$$" > "$1"
mode=$2
pages=0
while IFS= read -r line; do
    id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
    [ -n "$id" ] || continue
    case "$line" in
        *'"initialize"'*)
            result='{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"test","version":"1"}}'
            ;;
        *'tools/list'*)
            if [ "$mode" = slow-pages ]; then
                sleep 0.65
                pages=$((pages+1))
                result="{\"tools\":[],\"nextCursor\":\"page$pages\"}"
            else
                result='{"tools":[]}'
            fi
            ;;
        *)
            sleep 1.5
            result='{"content":[{"type":"text","text":"ok"}]}'
            ;;
    esac
    printf '{"jsonrpc":"2.0","id":%s,"result":%s}\n' "$id" "$result"
done
