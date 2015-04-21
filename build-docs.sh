#!/bin/sh
VERSION="latest"

lein doc
(cd doc; make)

rm -rf /tmp/futura-doc/
mkdir -p /tmp/futura-doc/
mv doc/index.html /tmp/futura-doc/
mv doc/api /tmp/futura-doc/api

git checkout gh-pages;

rm -rf ./$VERSION
mv /tmp/futura-doc/ ./$VERSION

git add --all ./$VERSION
git commit -a -m "Update ${VERSION} doc"
