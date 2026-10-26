# Proxy tools

[Documentation index](../README.md) · [Project README](../../README.md)

Comet adds two helpers to Meteor's **Proxies** screen and one to the disconnect
screen.

- **Fetch Free Proxies** downloads up to 24 SOCKS4/SOCKS5 proxies from
  ProxyScrape's public list, keeps entries with at least 50% uptime and a measured
  latency of 1–3000 ms, puts residential ISPs first, adds them to Meteor's proxy
  list and tests them off-thread. Review the list and test a proxy before
  connecting; free proxies are untrusted and can see or alter your traffic.
- **Proxy links** (on by default) opens web links you click in chat or menus in a
  separate Chrome/Chromium or Firefox profile bound to Meteor's enabled proxy.
  Profiles live in `meteor-client/proxy-browser/`; the toggle is saved in
  `meteor-client/proxy-links.txt`. If no supported browser is found, the click
  falls back to the system browser and its normal connection.
- **Verification links:** when a disconnect screen contains a URL, Comet adds
  **Open Verify Link** and **Open Via Proxy** buttons. The direct button uses a
  clean local browser profile. The proxy button uses the enabled proxy and, with
  Chrome/Chromium, fills the username, ticks the confirmation box and submits once
  you have solved the captcha; it does not solve or bypass the captcha.

Proxy use and verification links are subject to the server's rules and terms of
service. Comet does not create accounts, solve captchas or bypass bans. Check a
free proxy's location and trustworthiness before using it.
