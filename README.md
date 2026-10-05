# sprout-accounts

Opening a Sprout trading account, with a simulated KYC:

- 18 or older (on today's date in India);
- an individual's PAN (`AAAPA1234A`: the fourth letter is `P`), and one Sprout account per PAN;
- a Sprout Bank UPI address that exists (checked with the bank) for money to come from and go back to.

The PAN is never stored in clear: only masked (`XXXXX1234K`) for display and as a keyed hash
(HMAC with a server-side pepper) to enforce one account per PAN. Other Sprout services read accounts
on `/internal` with the service key.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`accounts-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/accounts-v1.yaml)
in sprout-contracts. It runs inside the **money** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed), every JSON response checked against
the contract.

## License

MIT
