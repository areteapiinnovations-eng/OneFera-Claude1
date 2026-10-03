#!/usr/bin/env bash
# Prints the latest stable version of key Android dependencies (used to keep gradle/libs.versions.toml current).
set -u
g(){ # $1 = group path, $2 = artifact
  local v
  v=$(curl -fsSL "https://dl.google.com/dl/android/maven2/$1/$2/maven-metadata.xml" \
      | grep -oE '<version>[^<]+</version>' | sed -E 's#</?version>##g' \
      | grep -viE 'alpha|beta|rc|dev|eap' | sort -V | tail -1)
  echo "$1:$2 = ${v:-?}"
}
g com/android/tools/build gradle
g androidx/compose compose-bom
g com/google/firebase firebase-bom
g com/google/gms google-services
g com/google/firebase firebase-crashlytics-gradle
g androidx/activity activity-compose
g androidx/navigation navigation-compose
g androidx/lifecycle lifecycle-runtime-compose
g androidx/core core-ktx
g androidx/core core-splashscreen
g androidx/datastore datastore-preferences
g androidx/room room-runtime
g androidx/media3 media3-exoplayer
g androidx/hilt hilt-navigation-compose
g androidx/work work-runtime-ktx
g androidx/credentials credentials
g com/google/android/libraries/identity/googleid googleid
g com/google/android/gms play-services-location
g androidx/paging paging-compose
g androidx/camera camera-camera2
# TEMP: product catalogue probe
curl -fsS -m 30 'https://dummyjson.com/products?limit=0&select=title,brand,category,price,rating,thumbnail,images' | python3 -c "
import sys,json
for p in json.load(sys.stdin)['products']:
    print('PRODUCT', json.dumps(p))
" || echo 'PRODUCT probe failed'
for u in https://cdn.dummyjson.com/product-images/smartphones/iphone-13-pro/thumbnail.webp; do curl -s -o /dev/null -w "IMG %{http_code} %{content_type} $u\n" "$u"; done
