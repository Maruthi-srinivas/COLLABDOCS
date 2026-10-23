const api = "http://collabdocs-restore-api:8080";
const headers = { "content-type": "application/json", "X-CollabDocs-Request": "1" };
const login = await fetch(`${api}/api/v1/auth/login`, {
  method: "POST",
  headers,
  body: JSON.stringify({ email: process.env.EMAIL, password: process.env.PASSWORD }),
});
const loginText = await login.text();
if (!login.ok) {
  console.log(`login ${login.status} ${loginText.slice(0, 180)}`);
  process.exit(2);
}
const access = JSON.parse(loginText).accessToken;
const doc = await fetch(`${api}/api/v1/documents/${process.env.DOCID}`, {
  headers: { Authorization: `Bearer ${access}`, "X-CollabDocs-Request": "1" },
});
const text = await doc.text();
if (doc.ok && text.includes(process.env.TITLE)) {
  console.log("opened");
  process.exit(0);
}
console.log(`doc ${doc.status} ${text.slice(0, 180)}`);
process.exit(3);
