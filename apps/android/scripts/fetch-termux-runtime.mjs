#!/usr/bin/env node
/**
 * Fetches the on-device Orion runtime for arm64 Android from the Termux package repository:
 * Node (LTS), git and OpenSSH plus their shared libraries.
 *
 * Android 10+ will not exec files from an app's data directory, so executables are shipped as
 * `lib<name>.so` in jniLibs (extracted to the read-only native library directory, where exec is
 * allowed) and every shared library goes into a tarball unpacked at first run (dlopen from the
 * data directory is allowed).
 *
 *   node apps/android/scripts/fetch-termux-runtime.mjs
 */
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const app = resolve(here, "../app/src/main");
const jniDir = join(app, "jniLibs/arm64-v8a");
const assetDir = join(app, "assets/orion-runtime");
const work = join(resolve(here, ".."), "build/termux-runtime");
const REPO = "https://packages.termux.dev/apt/termux-main";
const ROOTS = ["nodejs-lts", "git", "openssh"];
// Needed only for sshd, paging, or other interactive extras.
const SKIP = new Set(["less", "termux-auth", "openssh-sftp-server", "resolv-conf", "termux-tools", "termux-exec"]);
// Executables exposed through jniLibs: name → path inside the Termux prefix.
const EXECUTABLES = {
  node: "bin/node",
  git: "bin/git",
  "git-remote-http": "libexec/git-core/git-remote-http",
  ssh: "bin/ssh",
  "ssh-keygen": "bin/ssh-keygen",
};

async function download(url, dest) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  writeFileSync(dest, Buffer.from(await res.arrayBuffer()));
}

function parseIndex(text) {
  const pkgs = new Map();
  for (const block of text.split(/\n\n+/)) {
    const fields = {};
    for (const line of block.split("\n")) {
      const m = /^([A-Za-z-]+): (.*)$/.exec(line);
      if (m) fields[m[1]] = m[2];
    }
    if (fields.Package) pkgs.set(fields.Package, fields);
  }
  return pkgs;
}

function deps(pkg) {
  return (pkg.Depends ?? "")
    .split(",")
    .map((d) => d.split("|")[0].replace(/\(.*\)/, "").trim())
    .filter(Boolean);
}

mkdirSync(work, { recursive: true });
const indexPath = join(work, "Packages");
await download(`${REPO}/dists/stable/main/binary-aarch64/Packages`, indexPath);
const index = parseIndex(readFileSync(indexPath, "utf8"));

const closure = new Map();
const queue = [...ROOTS];
while (queue.length) {
  const name = queue.pop();
  if (closure.has(name) || SKIP.has(name)) continue;
  const pkg = index.get(name);
  if (!pkg) throw new Error(`Termux package not found: ${name}`);
  closure.set(name, pkg);
  queue.push(...deps(pkg));
}

const stage = join(work, "stage");
rmSync(stage, { recursive: true, force: true });
mkdirSync(stage, { recursive: true });
for (const [name, pkg] of closure) {
  const deb = join(work, `${name}.deb`);
  await download(`${REPO}/${pkg.Filename}`, deb);
  execFileSync("dpkg-deb", ["-x", deb, stage]);
  console.log(`${name} ${pkg.Version}`);
}

const prefix = join(stage, "data/data/com.termux/files/usr");
rmSync(jniDir, { recursive: true, force: true });
mkdirSync(jniDir, { recursive: true });
for (const [name, rel] of Object.entries(EXECUTABLES)) {
  const src = join(prefix, rel);
  if (!existsSync(src)) throw new Error(`missing ${rel} in the Termux packages`);
  copyFileSync(realpathSync(src), join(jniDir, `lib${name}.so`));
}

// Flat library directory plus the CA bundle; symlinks are resolved so the tar needs no link support.
const bundle = join(work, "bundle");
rmSync(bundle, { recursive: true, force: true });
mkdirSync(join(bundle, "lib"), { recursive: true });
mkdirSync(join(bundle, "etc"), { recursive: true });
for (const f of readdirSync(join(prefix, "lib"))) {
  if (!/\.so(\.|$)/.test(f)) continue;
  const p = join(prefix, "lib", f);
  const real = realpathSync(p);
  if (statSync(real).isFile()) copyFileSync(real, join(bundle, "lib", f));
}
for (const candidate of ["etc/tls/cert.pem", "etc/ssl/cert.pem"]) {
  if (existsSync(join(prefix, candidate))) {
    copyFileSync(realpathSync(join(prefix, candidate)), join(bundle, "etc/cert.pem"));
    break;
  }
}
mkdirSync(assetDir, { recursive: true });
const tarPath = join(assetDir, "libs.tar.gz");
execFileSync("tar", ["-czf", tarPath, "-C", bundle, "."]);
const sha = createHash("sha256").update(readFileSync(tarPath)).digest("hex");
writeFileSync(
  join(assetDir, "runtime.json"),
  `${JSON.stringify({ source: "termux", libsSha256: sha, packages: Object.fromEntries([...closure].map(([n, p]) => [n, p.Version])), executables: Object.keys(EXECUTABLES) }, null, 2)}\n`,
);
console.log(`runtime ready: ${closure.size} packages, libs.tar.gz ${(statSync(tarPath).size / 1e6).toFixed(1)} MB`);
