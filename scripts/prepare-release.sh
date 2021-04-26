
root="$(dirname $(dirname $(realpath "$0")))"

cd "$root" || exit

./mvnw gitflow:release-start

# update readme.adoc
./mvnw process-resources

git add --ignore-errors -A -f -- readme.adoc
git commit --amend --no-edit
./mvnw gitflow:release-finish
