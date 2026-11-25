# Security

Please report a vulnerability privately, through GitHub: the repository's **Security** tab, **Report a vulnerability**. Do not open a public issue for it.

Say what an attacker can do, and how to reproduce it. You will get an answer within a week.

msgforge has no login by design. Anyone who can reach its port can use it, so a report that depends on reaching the port is in scope only where the README says msgforge keeps others out: requests from other web pages in the user's browser, host names that are not allowed, environment variables and secrets that should not come back out.
