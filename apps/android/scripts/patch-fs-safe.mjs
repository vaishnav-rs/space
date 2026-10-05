#!/usr/bin/env node
/**
 * Android denies hard links to app processes (SELinux EACCES). fs-safe's copy publication links a private,
 * completed stage to its destination and then unlinks the stage; with links denied the SQLite snapshot the
 * gateway takes at start-up fails. This patches the on-device gateway copy of fs-safe so that, only when the
 * link itself is refused, the stage is renamed into place instead. publishCopyStage has already verified the
 * destination is absent, and a rename keeps the stage's identity, so every later fence still holds.
 *
 *   node patch-fs-safe.mjs <gateway-dir-containing-node_modules>
 */
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const target = process.argv[2];
if (!target) {
  console.error("usage: patch-fs-safe.mjs <gateway-dir>");
  process.exit(2);
}

function findAll(dir, name, out = []) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    const stat = statSync(path, { throwIfNoEntry: false });
    if (!stat) continue;
    if (stat.isDirectory()) findAll(path, name, out);
    else if (entry === name && path.includes("fs-safe")) out.push(path);
  }
  return out;
}

const LINK = "    fs.linkSync(temporaryPath, targetPath);\n";
const LINK_PATCHED = `    let linked = true;
    try {
        fs.linkSync(temporaryPath, targetPath);
    }
    catch (error) {
        // Android: hard links are refused. The destination was just checked absent; rename keeps the stage identity.
        if (error?.code !== "EACCES" && error?.code !== "EPERM")
            throw error;
        fs.renameSync(temporaryPath, targetPath);
        linked = false;
    }
`;
const CLEAN_START = "        const parent = fs.lstatSync(path.dirname(temporaryPath), { bigint: true });\n";
const CLEAN_END = "        fs.unlinkSync(temporaryPath);\n";

const files = existsSync(join(target, "node_modules")) ? findAll(join(target, "node_modules"), "publish-copy-stage.js") : [];
if (files.length === 0) {
  console.error(`patch-fs-safe: publish-copy-stage.js not found under ${target}/node_modules`);
  process.exit(1);
}
for (const file of files) {
  let src = readFileSync(file, "utf8");
  if (src.includes("let linked = true;")) {
    console.log(`already patched: ${file}`);
    continue;
  }
  if (!src.includes(LINK) || !src.includes(CLEAN_START) || !src.includes(CLEAN_END)) {
    console.error(`patch-fs-safe: unexpected publish-copy-stage.js layout in ${file}; update this patch for the new fs-safe version`);
    process.exit(1);
  }
  src = src.replace(LINK, LINK_PATCHED);
  src = src.replace(CLEAN_START, `        if (linked) {\n${CLEAN_START}`);
  src = src.replace(CLEAN_END, `${CLEAN_END}        }\n`);
  writeFileSync(file, src);
  console.log(`patched: ${file}`);
}
