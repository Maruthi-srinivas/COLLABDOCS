export function decode(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export function encode(bytes) {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

export function planWelcome(localEpoch, applied, welcome) {
  const sameEpoch = localEpoch != null && welcome.epoch === localEpoch;
  if (localEpoch != null && !sameEpoch) {
    return { reset: true, applySnapshot: true, updates: welcome.updates || [] };
  }
  return {
    reset: false,
    applySnapshot: localEpoch == null && Boolean(welcome.snapshotBase64),
    updates: (welcome.updates || []).filter((update) => !applied.has(update.seq)),
  };
}
