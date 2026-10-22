#!/bin/sh
for argument in "$@"; do
    shift
    case "$argument" in
        -Wl,*.def) set -- "$@" "${argument#-Wl,}" ;;
        *) set -- "$@" "$argument" ;;
    esac
done
exec cargo-zigbuild zig cc -- -target x86_64-windows-gnu "$@"
