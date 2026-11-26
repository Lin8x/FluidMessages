# Fedora COPR builds the source package with this (Build method: make_srpm). The version is the latest release tag,
# and the release counts the commits since it, so every build of main is newer than the one before.
# Try it locally (needs git and rpm-build): make -f .copr/Makefile srpm outdir=/tmp/srpm
outdir ?= $(CURDIR)
spec = deploy/fedora/fluidmessages-web.spec

srpm:
	command -v git >/dev/null && command -v rpmbuild >/dev/null || dnf -y install git rpm-build
	git config --global --add safe.directory "$$(pwd)"
	set -e; tag=$$(git describe --tags --abbrev=0 --match 'v[0-9]*'); version=$${tag#v}; \
	release=$$(( $$(git rev-list --count $$tag..HEAD) + 1 )); \
	work=$$(mktemp -d); mkdir -p $$work/SOURCES $$work/SPECS; \
	git archive --format=tar.gz --prefix=fluidmessages-web-$$version/ -o $$work/SOURCES/fluidmessages-web-$$version.tar.gz HEAD; \
	sed -e "s/@VERSION@/$$version/" -e "s/@RELEASE@/$$release/" $(spec) > $$work/SPECS/fluidmessages-web.spec; \
	rpmbuild -bs --define "_topdir $$work" --define "_srcrpmdir $(outdir)" $$work/SPECS/fluidmessages-web.spec
