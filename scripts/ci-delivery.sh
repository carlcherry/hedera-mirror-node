#!/bin/sh -ex

mkdir -p mirror-node
tar zxvf mirror-node.tgz --strip 1 -C mirror-node
cd mirror-node