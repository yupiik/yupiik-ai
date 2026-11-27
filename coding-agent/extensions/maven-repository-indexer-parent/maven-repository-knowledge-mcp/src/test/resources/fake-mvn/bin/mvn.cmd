@REM
@REM Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
@REM Licensed under the Apache License, Version 2.0 (the "License");
@REM you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM  http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM
@REM Fake Maven used by the MCP server integration tests (Windows counterpart of
@REM `mvn`): replaces the real `mvn` on PATH with a constant, deterministic
@REM `dependency:list` output so tests never spawn the real build tool.
@REM See MvnProjectDeps.resolve().
@echo off
echo [INFO] io.yupiik.fake:fake-util:jar:2.0:provided
echo [INFO] io.yupiik.fake:fake-lib:jar:1.0:compile
exit /b 0