// Build-time test only (never shipped): makes hard links fail with EACCES like Android does, so the
// gateway smoke test in CI exercises the same fallback paths the phone needs.
const fs = require("node:fs");
function deny() {
  const error = new Error("EACCES: permission denied, link");
  error.code = "EACCES";
  error.errno = -13;
  error.syscall = "link";
  throw error;
}
fs.linkSync = deny;
fs.link = (_existing, _new, callback) => {
  try {
    deny();
  } catch (error) {
    callback(error);
  }
};
fs.promises.link = async () => deny();
