#!/usr/bin/env node
/* Keep the iOS project's build number in step with the web release train:
   ios/project.yml CURRENT_PROJECT_VERSION must equal version.txt (the same
   rule tools/qa.js enforces for index.html BUILD_NUM).
   Usage:  node tools/sync-ios-version.js --check   (CI gate, exit 1 on drift)
           node tools/sync-ios-version.js --write   (update project.yml) */
"use strict";
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const vt = fs.readFileSync(path.join(root, "version.txt"), "utf8").trim();
const projPath = path.join(root, "ios", "project.yml");
let proj = fs.readFileSync(projPath, "utf8");

if (!/^\d+$/.test(vt)) {
  console.error("version.txt is not a plain integer: " + vt);
  process.exit(1);
}

const re = /(CURRENT_PROJECT_VERSION:\s*")(\d+)(")/;
const m = proj.match(re);
if (!m) {
  console.error("ios/project.yml has no CURRENT_PROJECT_VERSION entry");
  process.exit(1);
}

const mode = process.argv[2] || "--check";
if (mode === "--write") {
  proj = proj.replace(new RegExp(re.source, "g"), "$1" + vt + "$3");
  fs.writeFileSync(projPath, proj);
  console.log("ios/project.yml CURRENT_PROJECT_VERSION -> " + vt);
} else if (m[2] === vt) {
  console.log("ios build number " + m[2] + " == version.txt");
} else {
  console.error("ios build number (" + m[2] + ") != version.txt (" + vt + ") -- run: node tools/sync-ios-version.js --write");
  process.exit(1);
}
