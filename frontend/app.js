const API_BASE = window.SMARTTUBE_API_BASE || "";

const endpoints = {
  auth: `${API_BASE}/api/auth/status`,
  login: `${API_BASE}/api/auth/login`,
  logout: `${API_BASE}/api/auth/logout`,
  subscriptions: `${API_BASE}/api/subscriptions`,
  subscriptionsFeed: `${API_BASE}/api/subscriptions/feed`,
  playlists: `${API_BASE}/api/playlists`,
  watchLater: `${API_BASE}/api/watch-later`,
  playlist: (playlistId) => `${API_BASE}/api/playlists/${encodeURIComponent(playlistId)}`,
  videos: (videoId) => `${API_BASE}/api/videos/${encodeURIComponent(videoId)}`,
  segments: (videoId) => `${API_BASE}/api/videos/${encodeURIComponent(videoId)}/segments`,
};

const toastContainer = document.querySelector("#toast-container");
const accountButton = document.querySelector("#account-button");
const accountPopover = document.querySelector("#account-popover");
const accountAvatar = document.querySelector("#account-avatar");
const authTitle = document.querySelector("#auth-title");
const authStatus = document.querySelector("#auth-status");
const authDetails = document.querySelector("#auth-details");
const authDeviceFlow = document.querySelector("#auth-device-flow");
const authDeviceCode = document.querySelector("#auth-device-code");
const authDeviceLink = document.querySelector("#auth-device-link");
const authDeviceStatus = document.querySelector("#auth-device-status");
const authActionButton = document.querySelector("#auth-action-button");

const navButtons = Array.from(document.querySelectorAll(".nav-link[data-route]"));
const routeSections = Array.from(document.querySelectorAll("[data-route-section]"));
const pageTitle = document.querySelector("#page-title");
const pageMeta = document.querySelector("#page-meta");
const browseGrid = document.querySelector("#browse-grid");
const libraryMeta = document.querySelector("#library-meta");
const libraryList = document.querySelector("#library-list");
const playlistTitle = document.querySelector("#playlist-title");
const playlistMeta = document.querySelector("#playlist-meta");
const playlistGrid = document.querySelector("#playlist-grid");

const videoPlayer = document.querySelector("#video-player");
const videoMeta = document.querySelector("#video-meta");
const watchTitle = document.querySelector("#watch-title");
const watchChannel = document.querySelector("#watch-channel");
const watchMetadata = document.querySelector("#watch-metadata");
const watchLaterButton = document.querySelector("#watch-later-toggle");
const qualitySelect = document.querySelector("#quality-select");
const skipIndicator = document.querySelector("#skip-indicator");
const skipText = document.querySelector("#skip-text");

const appState = {
  auth: null,
  route: { name: "subscriptions" },
  subscriptionsItems: [],
  watchLaterItems: [],
  watchLaterSupported: true,
  currentVideoId: null,
  currentFormat: "bestvideo+bestaudio/best",
  apiCache: new Map(),
};

let authPollTimer = null;
let currentSegments = [];
let skipCooldown = false;
let hls = null;

function setBanner(message, isError = false) {
  if (!toastContainer) {
    return;
  }

  const toast = document.createElement("div");
  toast.className = isError ? "toast error-state" : "toast";
  toast.setAttribute("role", isError ? "alert" : "status");
  toast.textContent = message;
  toastContainer.append(toast);

  window.setTimeout(() => {
    toast.classList.add("toast-exit");
    window.setTimeout(() => toast.remove(), 200);
  }, isError ? 6000 : 3000);
}

function setMetaText(element, text) {
  if (!element) {
    return;
  }

  element.textContent = text || "";
  element.classList.toggle("hidden", !text);
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    headers: {
      Accept: "application/json",
      ...(options.headers || {}),
    },
    ...options,
  });

  if (!response.ok) {
    let details = `${response.status} ${response.statusText}`;

    try {
      const payload = await response.json();
      details = payload?.message || payload?.error || details;
    } catch {
      // Keep the original status text.
    }

    const error = new Error(details);
    error.status = response.status;
    throw error;
  }

  if (response.status === 204) {
    return null;
  }

  return response.headers.get("content-type")?.includes("application/json")
    ? response.json()
    : null;
}

async function fetchCachedJson(url, options = {}) {
  const cacheKey = options.cacheKey || url;
  const ttlMs = options.ttlMs ?? 5 * 60 * 1000;
  const force = Boolean(options.force);
  const cached = appState.apiCache.get(cacheKey);

  if (!force && cached && Date.now() - cached.timestamp < ttlMs) {
    return cached.payload;
  }

  const payload = await fetchJson(url);
  appState.apiCache.set(cacheKey, {
    payload,
    timestamp: Date.now(),
  });
  return payload;
}

function invalidateCache(...keys) {
  if (!keys.length) {
    appState.apiCache.clear();
    return;
  }

  for (const key of keys) {
    appState.apiCache.delete(key);
  }
}

function postJson(url, body = {}) {
  return fetchJson(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

function deleteJson(url, body) {
  return fetchJson(url, {
    method: "DELETE",
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
}

function normalizeListPayload(payload) {
  if (Array.isArray(payload)) {
    return payload;
  }

  if (Array.isArray(payload?.items)) {
    return payload.items;
  }

  if (Array.isArray(payload?.item?.items)) {
    return payload.item.items;
  }

  return [];
}

function normalizeAuthPayload(payload) {
  return {
    signedIn: Boolean(payload?.signedIn ?? payload?.authenticated ?? payload?.isAuthenticated),
    userName: payload?.userName ?? payload?.name ?? payload?.user?.displayName ?? payload?.user?.name ?? "Guest",
    email: payload?.email ?? payload?.user?.email ?? "Not available",
    channel: payload?.channel ?? payload?.channelName ?? payload?.user?.channel ?? payload?.provider ?? "Not available",
  };
}

function normalizeVideoItem(item) {
  const thumbnails = item?.thumbnails || item?.thumbnail || item?.thumbnailUrl || item?.images || [];
  const thumbnailUrl = Array.isArray(thumbnails)
    ? thumbnails[thumbnails.length - 1]?.url || thumbnails[0]?.url || ""
    : typeof thumbnails === "string"
      ? thumbnails
      : thumbnails?.url || "";

  return {
    videoId: item?.videoId ?? item?.id ?? item?.contentId ?? null,
    title: item?.title ?? item?.name ?? "Untitled video",
    channelName: item?.channelName ?? item?.author ?? item?.channel ?? item?.ownerText ?? "Unknown channel",
    thumbnailUrl,
    durationLabel: item?.durationLabel ?? item?.lengthText ?? "",
    metadataLabel: item?.metadataLabel ?? item?.publishedTimeText ?? item?.publishedAt ?? item?.uploadTime ?? item?.viewCountText ?? "",
    setVideoId: item?.setVideoId ?? item?.playlistItemId ?? item?.playlistSetVideoId ?? null,
  };
}

function renderPairs(target, pairs) {
  target.replaceChildren();

  for (const [label, value] of pairs) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    target.append(dt, dd);
  }
}

function renderEmpty(target, message, isError = false) {
  target.replaceChildren();
  const element = document.createElement("div");
  element.className = isError ? "error-state" : "empty-state";
  element.textContent = message;
  target.append(element);
}

function createVideoCard(rawItem, options = {}) {
  const item = normalizeVideoItem(rawItem);
  const card = document.createElement("article");
  card.className = "video-card";

  if (item.videoId) {
    card.dataset.videoId = item.videoId;
  }
  if (item.setVideoId) {
    card.dataset.setVideoId = item.setVideoId;
  }

  const thumbButton = document.createElement("button");
  thumbButton.className = "video-card-thumb";
  thumbButton.type = "button";
  thumbButton.setAttribute("aria-label", `Open ${item.title}`);

  if (item.thumbnailUrl) {
    const img = document.createElement("img");
    img.src = item.thumbnailUrl;
    img.alt = "";
    img.loading = "lazy";
    thumbButton.append(img);
  } else {
    const fallback = document.createElement("div");
    fallback.className = "video-card-fallback";
    fallback.textContent = "No thumbnail";
    thumbButton.append(fallback);
  }

  if (item.durationLabel) {
    const duration = document.createElement("span");
    duration.className = "duration-badge";
    duration.textContent = item.durationLabel;
    thumbButton.append(duration);
  }

  const body = document.createElement("div");
  body.className = "video-card-body";

  const title = document.createElement("h3");
  title.className = "video-card-title";
  title.textContent = item.title;

  const channel = document.createElement("p");
  channel.className = "video-card-meta";
  channel.textContent = item.channelName;

  const metadata = document.createElement("p");
  metadata.className = "video-card-subtitle";
  metadata.textContent = item.metadataLabel || "";

  body.append(title, channel, metadata);

  if (options.watchLaterAction && appState.watchLaterSupported) {
    const actions = document.createElement("div");
    actions.className = "video-card-actions";
    const button = document.createElement("button");
    button.className = "ghost-button watch-later-action";
    button.type = "button";
    button.dataset.action = options.watchLaterAction;
    button.textContent = options.watchLaterAction === "remove" ? "Remove" : "Save";
    actions.append(button);
    body.append(actions);
  }

  card.append(thumbButton, body);
  return card;
}

function renderVideoGrid(target, items, emptyMessage, options = {}) {
  target.replaceChildren();

  if (!items.length) {
    renderEmpty(target, emptyMessage, options.isError);
    return;
  }

  for (const item of items) {
    target.append(createVideoCard(item, options));
  }
}

function renderLibrary(items) {
  libraryList.replaceChildren();

  if (!items.length) {
    const li = document.createElement("li");
    li.className = "empty-state";
    li.textContent = "No playlists available.";
    libraryList.append(li);
    return;
  }

  for (const item of items) {
    const li = document.createElement("li");
    li.className = "card-item clickable";
    li.dataset.playlistId = item?.playlistId || item?.id || "";

    const title = document.createElement("strong");
    title.textContent = item?.title ?? item?.name ?? "Untitled playlist";
    const count = document.createElement("p");
    const itemCount = item?.itemCount ?? item?.videoCount ?? item?.items?.length ?? 0;
    count.textContent = `${itemCount} video${itemCount === 1 ? "" : "s"}`;

    li.append(title, count);
    libraryList.append(li);
  }
}

function parseRoute() {
  const hash = window.location.hash || "#/subscriptions";
  const parts = hash.replace(/^#\/?/, "").split("/").filter(Boolean);
  const name = parts[0] || "subscriptions";

  if (name === "watch" && parts[1]) {
    return { name: "watch", videoId: decodeURIComponent(parts[1]) };
  }

  if (name === "playlist" && parts[1]) {
    return { name: "playlist", playlistId: decodeURIComponent(parts[1]) };
  }

  if (["subscriptions", "library", "watch-later"].includes(name)) {
    return { name };
  }

  return { name: "subscriptions" };
}

function activateSection(sectionName) {
  const activeSection = ["subscriptions", "watch-later"].includes(sectionName) ? "browse" : sectionName;

  for (const section of routeSections) {
    section.classList.toggle("hidden", section.dataset.routeSection !== activeSection);
  }

  for (const button of navButtons) {
    button.classList.toggle("active", button.dataset.route === sectionName);
  }
}

function routeTitle(route) {
  return {
    subscriptions: "Subscriptions",
    library: "Library",
    "watch-later": "Watch Later",
    playlist: "Playlist",
    watch: "Watch",
  }[route.name] || "Subscriptions";
}

async function dispatchRoute(options = {}) {
  const route = parseRoute();
  appState.route = route;
  document.title = `VibeTube | ${routeTitle(route)}`;

  if (route.name !== "watch") {
    stopPlayback();
  }

  if (route.name === "playlist") {
    activateSection("playlist");
    return renderPlaylistVideos(route.playlistId, options);
  }

  if (route.name === "watch") {
    activateSection("watch");
    return renderWatchPage(route.videoId);
  }

  activateSection(route.name);

  if (route.name === "subscriptions") {
    return renderSubscriptionsFeed(options);
  } else if (route.name === "library") {
    return loadLibrary(options);
  } else if (route.name === "watch-later") {
    return renderWatchLater(options);
  }

  return false;
}

function navigateTo(route) {
  if (window.location.hash === route) {
    dispatchRoute();
    return;
  }

  window.location.hash = route;
}

function setAccountSummary(auth) {
  appState.auth = auth;
  accountAvatar.textContent = (auth.userName || "Guest").trim().charAt(0).toUpperCase() || "G";
  accountButton.title = auth.signedIn ? `${auth.userName} (${auth.email})` : "Sign in to VibeTube";
  accountButton.setAttribute("aria-label", auth.signedIn ? `Account for ${auth.userName}` : "Sign in to VibeTube");
  if (authTitle) {
    authTitle.textContent = auth.signedIn ? auth.userName : "Account Status";
  }
  if (authActionButton) {
    authActionButton.dataset.action = auth.signedIn ? "sign-out" : "sign-in";
    authActionButton.textContent = auth.signedIn ? "Sign out" : "Sign in";
    authActionButton.classList.toggle("primary-button", !auth.signedIn);
    authActionButton.classList.toggle("ghost-button", auth.signedIn);
  }
}

function clearAuthPoll() {
  if (authPollTimer) {
    window.clearTimeout(authPollTimer);
    authPollTimer = null;
  }
}

function hideDeviceFlow() {
  clearAuthPoll();
  authDeviceFlow.classList.add("hidden");
  authDeviceCode.textContent = "-";
  authDeviceLink.textContent = "";
  authDeviceStatus.textContent = "Waiting to start sign-in.";
}

function showDeviceFlow(device) {
  authDeviceFlow.classList.remove("hidden");
  authDeviceCode.textContent = device.userCode;
  authDeviceLink.innerHTML = `<a href="${device.verificationUrl}" target="_blank" rel="noreferrer">${device.verificationUrl}</a>`;
  authDeviceStatus.textContent = "Enter the code on the opened activation page, then wait for automatic polling.";
}

async function loadAuth() {
  authStatus.textContent = "Loading...";
  authDetails.replaceChildren();

  try {
    const payload = await fetchJson(endpoints.auth);
    const auth = normalizeAuthPayload(payload);
    authStatus.textContent = auth.signedIn ? "Signed in" : "Signed out";
    setAccountSummary(auth);
    if (auth.signedIn) {
      hideDeviceFlow();
    }
    renderPairs(authDetails, [
      ["User", auth.userName],
      ["Email", auth.email],
      ["Channel", auth.channel],
      ["Endpoint", endpoints.auth],
    ]);
    return true;
  } catch (error) {
    authStatus.textContent = "Unavailable";
    setAccountSummary({ signedIn: false, userName: "Guest" });
    renderPairs(authDetails, [
      ["Error", error.message],
      ["Endpoint", endpoints.auth],
    ]);
    return false;
  }
}

async function renderSubscriptionsFeed(options = {}) {
  pageTitle.textContent = "Subscriptions";

  try {
    const payload = await fetchCachedJson(endpoints.subscriptionsFeed, {
      cacheKey: "subscriptions-feed",
      force: options.force,
    });
    const items = normalizeListPayload(payload);
    appState.subscriptionsItems = items;
    setMetaText(pageMeta, `${items.length} video${items.length === 1 ? "" : "s"}`);
    renderVideoGrid(browseGrid, items, "No subscription videos available.", { watchLaterAction: "add" });
    return true;
  } catch (error) {
    appState.subscriptionsItems = [];
    setMetaText(pageMeta, error.status === 401 ? "Sign in to load subscriptions." : "Failed to load subscription feed.");
    renderVideoGrid(browseGrid, [], error.message, { isError: true });
    return false;
  }
}

async function loadLibrary(options = {}) {
  try {
    const payload = await fetchCachedJson(endpoints.playlists, {
      cacheKey: "library",
      force: options.force,
    });
    const items = normalizeListPayload(payload);
    setMetaText(libraryMeta, `${items.length} playlist${items.length === 1 ? "" : "s"}`);
    renderLibrary(items);
    return true;
  } catch (error) {
    setMetaText(libraryMeta, error.status === 401 ? "Sign in to load Library." : "Failed to load Library.");
    libraryList.replaceChildren();
    const li = document.createElement("li");
    li.className = "error-state";
    li.textContent = error.message;
    libraryList.append(li);
    return false;
  }
}

async function renderWatchLater(options = {}) {
  pageTitle.textContent = "Watch Later";

  try {
    const payload = await fetchCachedJson(endpoints.watchLater, {
      cacheKey: "watch-later",
      force: options.force,
    });
    const items = normalizeListPayload(payload);
    appState.watchLaterItems = items;
    appState.watchLaterSupported = true;
    setMetaText(pageMeta, `${items.length} video${items.length === 1 ? "" : "s"}`);
    renderVideoGrid(browseGrid, items, "Your Watch Later list is empty.", { watchLaterAction: "remove" });
    return true;
  } catch (error) {
    appState.watchLaterItems = [];
    appState.watchLaterSupported = error.status !== 404;
    setMetaText(pageMeta, error.status === 401 ? "Sign in to load Watch Later." : "Failed to load Watch Later.");
    renderVideoGrid(browseGrid, [], error.message, { isError: true });
    return false;
  }
}

async function renderPlaylistVideos(playlistId, options = {}) {
  playlistTitle.textContent = "Playlist";

  try {
    const payload = await fetchCachedJson(endpoints.playlist(playlistId), {
      cacheKey: `playlist:${playlistId}`,
      force: options.force,
    });
    const playlist = payload?.item || payload;
    const items = normalizeListPayload(payload);
    playlistTitle.textContent = playlist?.title || "Playlist";
    setMetaText(playlistMeta, `${items.length} video${items.length === 1 ? "" : "s"}`);
    renderVideoGrid(playlistGrid, items, "No videos in this playlist.", { watchLaterAction: "add" });
    return true;
  } catch (error) {
    setMetaText(playlistMeta, "Failed to load playlist.");
    renderVideoGrid(playlistGrid, [], error.message, { isError: true });
    return false;
  }
}

async function refreshCurrentRoute() {
  setBanner("Refreshing backend data...");
  invalidateCache();
  const authOk = await loadAuth();
  const routeOk = await dispatchRoute({ force: true });
  setBanner(authOk && routeOk ? "Connected to backend APIs." : "Some backend calls failed.", !(authOk && routeOk));
}

async function signIn() {
  setBanner("Starting VibeTube sign-in...");
  invalidateCache();

  try {
    const payload = await postJson(endpoints.login, { provider: "google" });
    showDeviceFlow(payload.device);
    accountPopover.classList.remove("hidden");
    accountButton.setAttribute("aria-expanded", "true");
    setBanner("Device code generated. Complete activation from the account menu.");
    pollAuthCompletion(payload.device.loginId, payload.device.intervalSeconds);
  } catch (error) {
    setBanner(`Sign-in failed: ${error.message}`, true);
  }
}

async function signOut() {
  setBanner("Signing out from backend session...");
  invalidateCache();

  try {
    await postJson(endpoints.logout);
    hideDeviceFlow();
    await refreshCurrentRoute();
    setBanner("Sign-out completed.");
  } catch (error) {
    setBanner(`Sign-out failed: ${error.message}`, true);
  }
}

async function toggleAuthAction() {
  if (authActionButton?.dataset.action === "sign-out") {
    await signOut();
    return;
  }

  await signIn();
}

async function pollAuthCompletion(loginId, intervalSeconds) {
  clearAuthPoll();

  try {
    const payload = await fetchJson(`${endpoints.login}/${loginId}`);

    if (payload.completed) {
      hideDeviceFlow();
      invalidateCache();
      await refreshCurrentRoute();
      setBanner("VibeTube login completed.");
      return;
    }

    if (!payload.authorizationPending && payload.error) {
      authDeviceStatus.textContent = `Login failed: ${payload.error}`;
      setBanner(`Login failed: ${payload.error}`, true);
      return;
    }

    authDeviceStatus.textContent = "Waiting for sign-in confirmation...";
    authPollTimer = window.setTimeout(() => pollAuthCompletion(loginId, intervalSeconds), Math.max(intervalSeconds, 2) * 1000);
  } catch (error) {
    authDeviceStatus.textContent = `Polling failed: ${error.message}`;
    setBanner(`Login polling failed: ${error.message}`, true);
  }
}

function getCategoryLabel(category) {
  const labels = {
    SPONSOR: "Sponsor",
    SELFPROMO: "Self-promo",
    INTRO: "Intro",
    OUTRO: "Outro",
    MUSIC_OFFTOPIC: "Music off-topic",
  };
  return labels[category] || "Skipping";
}

async function loadVideoSegments(videoId) {
  try {
    const response = await fetchJson(endpoints.segments(videoId));
    return response.segments || [];
  } catch {
    return [];
  }
}

function handleTimeUpdate() {
  if (skipCooldown || currentSegments.length === 0) {
    return;
  }

  const currentTime = videoPlayer.currentTime;

  for (const segment of currentSegments) {
    if (currentTime >= segment.startTime && currentTime < segment.endTime) {
      videoPlayer.currentTime = segment.endTime;
      skipText.textContent = `Skipping ${getCategoryLabel(segment.category)}...`;
      skipIndicator.classList.remove("hidden");
      skipCooldown = true;

      window.setTimeout(() => {
        skipCooldown = false;
        skipIndicator.classList.add("hidden");
      }, 500);
      break;
    }
  }
}

function attachSubtitleTracks(subtitles = []) {
  const tracks = Array.isArray(subtitles) ? subtitles : [];
  const defaultTrack = tracks.find((track) => track?.default) || tracks[0];

  for (const track of tracks) {
    if (!track?.url || !track?.language) {
      continue;
    }

    const trackElement = document.createElement("track");
    trackElement.kind = "subtitles";
    trackElement.src = track.url;
    trackElement.srclang = track.language;
    trackElement.label = track.label || track.language;
    trackElement.default = track === defaultTrack;
    if (trackElement.default) {
      trackElement.addEventListener("load", () => {
        trackElement.track.mode = "showing";
      }, { once: true });
    }
    videoPlayer.append(trackElement);
  }
}

function attachVideoSource(streamUrl, subtitles = []) {
  if (hls) {
    hls.destroy();
    hls = null;
  }

  videoPlayer.innerHTML = "";
  videoPlayer.removeAttribute("src");

  if (streamUrl.includes(".m3u8")) {
    if (window.Hls?.isSupported()) {
      hls = new window.Hls();
      hls.loadSource(streamUrl);
      hls.attachMedia(videoPlayer);
      attachSubtitleTracks(subtitles);
      hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
        videoPlayer.play().catch(() => {});
      });
      return;
    }

    if (videoPlayer.canPlayType("application/vnd.apple.mpegurl")) {
      videoPlayer.src = streamUrl;
      attachSubtitleTracks(subtitles);
      videoPlayer.addEventListener("loadedmetadata", () => {
        videoPlayer.play().catch(() => {});
      }, { once: true });
      return;
    }
  }

  const source = document.createElement("source");
  source.src = streamUrl;
  source.type = "video/mp4";
  videoPlayer.append(source);
  attachSubtitleTracks(subtitles);
  videoPlayer.load();
  videoPlayer.play().catch(() => {});
}

function stopPlayback() {
  if (!videoPlayer) {
    return;
  }

  videoPlayer.pause();
  videoPlayer.removeEventListener("timeupdate", handleTimeUpdate);
  if (hls) {
    hls.destroy();
    hls = null;
  }
  currentSegments = [];
}

async function loadVideoFormats(videoId, selectedFormat) {
  try {
    const payload = await fetchJson(`${endpoints.videos(videoId)}/formats`);
    const formats = Array.isArray(payload?.formats) ? payload.formats : [];

    qualitySelect.replaceChildren();

    const defaultOption = document.createElement("option");
    defaultOption.value = "bestvideo+bestaudio/best";
    defaultOption.textContent = "Best available";
    qualitySelect.append(defaultOption);

    if (!formats.length) {
      qualitySelect.value = selectedFormat || defaultOption.value;
      return payload;
    }

    for (const format of formats) {
      const option = document.createElement("option");
      option.value = String(format.formatId || format.itag);
      option.textContent = format.qualityLabel || `Format ${format.itag}`;
      qualitySelect.append(option);
    }

    const hasSelectedFormat = Array.from(qualitySelect.options).some((option) => option.value === selectedFormat);
    const selectedAutomaticDefault = selectedFormat === defaultOption.value;
    const preferredFormat = findPreferredFormat(formats);
    qualitySelect.value = hasSelectedFormat && !selectedAutomaticDefault
      ? selectedFormat
      : preferredFormat?.value || defaultOption.value;
    return payload;
  } catch {
    qualitySelect.replaceChildren();
    const option = document.createElement("option");
    option.value = selectedFormat || "bestvideo+bestaudio/best";
    option.textContent = "Best available";
    qualitySelect.append(option);
    return null;
  }
}

function findPreferredFormat(formats) {
  const parsedFormats = formats
    .map((format) => {
      const label = format.qualityLabel || "";
      const resolution = label.match(/(\d{3,4})\s*p/i)?.[1]
        || label.match(/\d{3,4}\s*x\s*(\d{3,4})/i)?.[1]
        || label.match(/\bHD\s*(\d{3,4})\b/i)?.[1]
        || label.match(/\b(\d{3,4})\b/)?.[1]
        || "";
      const height = Number.parseInt(resolution, 10) || 0;

      return {
        value: String(format.formatId || format.itag),
        height,
      };
    })
    .filter((format) => format.value && format.height > 0);

  return parsedFormats.find((format) => format.height === 1080)
    || [...parsedFormats].sort((left, right) => right.height - left.height)[0]
    || null;
}

async function renderWatchPage(videoId, formatOverride) {
  appState.currentVideoId = videoId;
  appState.currentFormat = formatOverride || appState.currentFormat;
  watchTitle.textContent = "Loading video...";
  watchChannel.textContent = "";
  watchMetadata.textContent = "";
  videoMeta.textContent = "";
  setBanner(`Loading video ${videoId}...`);

  try {
    currentSegments = await loadVideoSegments(videoId);
    const formatsPayload = await loadVideoFormats(videoId, appState.currentFormat);
    const resolvedFormat = qualitySelect.value || appState.currentFormat;
    appState.currentFormat = resolvedFormat;

    const videoData = await fetchJson(`${endpoints.videos(videoId)}?format=${encodeURIComponent(resolvedFormat)}`);
    if (!videoData?.streamUrl) {
      throw new Error(videoData?.error || "No stream URL available");
    }

    attachVideoSource(videoData.hlsManifestUrl || videoData.streamUrl, videoData.subtitles);
    videoPlayer.removeEventListener("timeupdate", handleTimeUpdate);
    videoPlayer.addEventListener("timeupdate", handleTimeUpdate);

    watchTitle.textContent = videoData.title || formatsPayload?.title || "Video";
    watchChannel.textContent = videoData.author || formatsPayload?.author || "Unknown channel";
    watchMetadata.textContent = videoData.lengthSeconds ? `${Math.round(videoData.lengthSeconds / 60)} min` : "";
    const subtitleCount = Array.isArray(videoData.subtitles) ? videoData.subtitles.length : 0;
    const sponsorBlockText = currentSegments.length > 0
      ? `${currentSegments.length} SponsorBlock segment${currentSegments.length === 1 ? "" : "s"} loaded`
      : "SponsorBlock has no segments for this video.";
    videoMeta.textContent = subtitleCount > 0
      ? `${sponsorBlockText} ${subtitleCount} subtitle track${subtitleCount === 1 ? "" : "s"} available.`
      : sponsorBlockText;
    updateWatchLaterButton(videoId);
    setBanner("Video loaded.");
    return true;
  } catch (error) {
    watchTitle.textContent = "Failed to load video";
    videoMeta.textContent = error.message;
    setBanner(`Failed to load video: ${error.message}`, true);
    return false;
  }
}

function updateWatchLaterButton(videoId) {
  const saved = appState.watchLaterItems.some((item) => normalizeVideoItem(item).videoId === videoId);
  watchLaterButton.dataset.action = saved ? "remove" : "add";
  watchLaterButton.textContent = saved ? "Remove from Watch Later" : "Save to Watch Later";
  watchLaterButton.disabled = !appState.watchLaterSupported;
}

async function requestWatchLaterUpdate(action, item) {
  if (action === "add") {
    await postJson(endpoints.watchLater, { videoId: item.videoId });
    return true;
  }

  await deleteJson(`${endpoints.watchLater}/${encodeURIComponent(item.videoId)}`, item.setVideoId ? { setVideoId: item.setVideoId } : undefined);
  return true;
}

async function handleWatchLaterAction(button) {
  const card = button.closest(".video-card");
  const item = {
    videoId: card?.dataset.videoId || appState.currentVideoId,
    setVideoId: card?.dataset.setVideoId,
  };

  if (!item.videoId) {
    return;
  }

  const action = button.dataset.action;
  button.disabled = true;
  button.textContent = action === "add" ? "Saving..." : "Removing...";

  try {
    await requestWatchLaterUpdate(action, item);
    invalidateCache("watch-later");
    await loadWatchLaterCache();
    if (appState.route.name === "watch-later") {
      await renderWatchLater();
    }
    if (appState.route.name === "watch") {
      updateWatchLaterButton(item.videoId);
    }
    setBanner(action === "add" ? "Saved to Watch Later." : "Removed from Watch Later.");
  } catch (error) {
    setBanner(error.message, true);
    button.disabled = false;
    button.textContent = action === "add" ? "Save" : "Remove";
  }
}

async function loadWatchLaterCache() {
  try {
    const payload = await fetchCachedJson(endpoints.watchLater, {
      cacheKey: "watch-later",
    });
    appState.watchLaterItems = normalizeListPayload(payload);
    appState.watchLaterSupported = true;
  } catch (error) {
    appState.watchLaterItems = [];
    appState.watchLaterSupported = error.status !== 404;
  }
}

document.querySelector("#refresh-all")?.addEventListener("click", refreshCurrentRoute);
document.querySelector(".topbar-search")?.addEventListener("submit", (event) => {
  event.preventDefault();
  setBanner("Search is not implemented yet.");
});
authActionButton?.addEventListener("click", toggleAuthAction);

qualitySelect?.addEventListener("change", async () => {
  if (appState.currentVideoId) {
    await renderWatchPage(appState.currentVideoId, qualitySelect.value);
  }
});

for (const button of navButtons) {
  button.addEventListener("click", () => navigateTo(button.dataset.hash));
}

accountButton.addEventListener("click", () => {
  const isHidden = accountPopover.classList.toggle("hidden");
  accountButton.setAttribute("aria-expanded", String(!isHidden));
});

document.addEventListener("click", (event) => {
  if (!accountPopover.classList.contains("hidden") && !event.target.closest(".account-menu")) {
    accountPopover.classList.add("hidden");
    accountButton.setAttribute("aria-expanded", "false");
  }
});

document.addEventListener("click", async (event) => {
  const watchLaterAction = event.target.closest(".watch-later-action, #watch-later-toggle");
  if (watchLaterAction) {
    await handleWatchLaterAction(watchLaterAction);
    return;
  }

  const videoCard = event.target.closest(".video-card[data-video-id]");
  if (videoCard && !event.target.closest(".video-card-actions")) {
    navigateTo(`#/watch/${encodeURIComponent(videoCard.dataset.videoId)}`);
    return;
  }

  const playlistCard = event.target.closest(".card-item[data-playlist-id]");
  if (playlistCard?.dataset.playlistId) {
    navigateTo(`#/playlist/${encodeURIComponent(playlistCard.dataset.playlistId)}`);
  }
});

window.addEventListener("hashchange", dispatchRoute);

hideDeviceFlow();
await Promise.all([loadAuth(), loadWatchLaterCache()]);
if (!window.location.hash) {
  history.replaceState(null, "", "#/subscriptions");
}
await dispatchRoute();
