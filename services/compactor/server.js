const http = require("http");

const port = Number(process.env.PORT || 8083);

const server = http.createServer((request, response) => {
  if (request.url === "/health/live") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ status: "up" }));
    return;
  }
  response.writeHead(404, { "content-type": "application/json" });
  response.end(JSON.stringify({ error: { code: "NOT_FOUND", message: "Not found" } }));
});

server.listen(port, "0.0.0.0");
