let accessToken = null;
let refreshing = null;

export function setAccessToken(token) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

async function send(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("X-CollabDocs-Request", "1");
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }
  if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(path, { ...options, headers, credentials: "include" });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  return { response, data };
}

export async function refreshSession() {
  if (!refreshing) {
    refreshing = send("/api/v1/auth/refresh", { method: "POST" })
      .then(({ response, data }) => {
        if (!response.ok) {
          accessToken = null;
          return false;
        }
        accessToken = data.accessToken;
        return true;
      })
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

export async function api(path, options = {}) {
  let result = await send(path, options);
  if (result.response.status === 401 && !options.skipRefresh && !path.startsWith("/api/v1/auth/refresh")) {
    const ok = await refreshSession();
    if (ok) {
      result = await send(path, options);
    }
  }
  if (!result.response.ok) {
    const error = new Error(result.data?.error?.message || "Request failed");
    error.status = result.response.status;
    error.code = result.data?.error?.code;
    throw error;
  }
  return result.data;
}
