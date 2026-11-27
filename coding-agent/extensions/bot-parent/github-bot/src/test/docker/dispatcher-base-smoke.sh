#!/bin/sh
# Manual image test; not bound to Maven. Requires Docker and public GitHub access.
set -eu
image=${1:?Usage: sh dispatcher-base-smoke.sh IMAGE [PLATFORM]}
platform=${2:-linux/amd64}
docker run --rm -i --platform "$platform" --read-only \
    --tmpfs /tmp:rw,nosuid,nodev,size=64m,mode=1777 \
    --cap-drop ALL --security-opt no-new-privileges \
    --entrypoint /bin/sh "$image" -seu <<'SMOKE'
test "$(id -u):$(id -g)" = 10001:10001
test "$HOME" = /tmp
test ! -w /usr
touch "$HOME/writable"
java -version
docker --version
git --version
for tool in javac cc gcc g++ clang dockerd; do
    if command -v "$tool"; then
        echo "Unexpected tool: $tool" >&2
        exit 1
    fi
done
test -s /etc/ssl/certs/ca-certificates.crt
exec_path=$(git --exec-path)
printf 'Git exec path: %s\n' "$exec_path"
for helper in git-remote-http git-remote-https git-http-backend; do
    test -x "$exec_path/$helper"
    ls -l "$exec_path/$helper"
done
git init --bare /tmp/repository.git
blob=$(printf 'dispatcher smoke\n' | git --git-dir=/tmp/repository.git hash-object -w --stdin)
git --git-dir=/tmp/repository.git update-ref refs/tags/smoke "$blob"
export GIT_PROJECT_ROOT=/tmp GIT_HTTP_EXPORT_ALL=1
export PATH_INFO=/repository.git/info/refs QUERY_STRING=service=git-upload-pack REQUEST_METHOD=GET
"$exec_path/git-http-backend" > /tmp/advertisement
grep -a 'Content-Type: application/x-git-upload-pack-advertisement' /tmp/advertisement
grep -a "$blob refs/tags/smoke" /tmp/advertisement
export PATH_INFO=/repository.git/git-upload-pack QUERY_STRING= REQUEST_METHOD=POST
export CONTENT_TYPE=application/x-git-upload-pack-request
printf '0032want %s\n00000009done\n' "$blob" > /tmp/request
export CONTENT_LENGTH=$(wc -c < /tmp/request)
"$exec_path/git-http-backend" < /tmp/request > /tmp/pack-response
grep -a 'Content-Type: application/x-git-upload-pack-result' /tmp/pack-response
grep -a -q PACK /tmp/pack-response
# These invoke both remote helpers, including TLS certificate verification.
git ls-remote https://github.com/octocat/Hello-World.git HEAD > /tmp/https-head
git ls-remote http://github.com/octocat/Hello-World.git HEAD > /tmp/http-head
grep HEAD /tmp/https-head
cmp /tmp/https-head /tmp/http-head
echo 'Dispatcher base smoke passed.'
SMOKE
