delete local `release` branch (**only** local)

run:

    mvn gitflow:release-start

enter the new release number

adjust the release version in the [readme.adoc](readme.adoc)

amend commit your changes

run:

    mvn gitflow:release-finish

hard reset the `release` branch to the currently created release and start release build from neo4j build server
