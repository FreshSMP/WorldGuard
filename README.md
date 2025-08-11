<h1>
    <img src="worldguard-logo.svg" alt="WorldGuard" width="400" /> 
</h1>

Fork of FreshSMP's async-move WG fork with per-world Event Whitelisting.
Usage example in `WorldGuard/worlds/world/config.yml`

```yml
events:
    whitelist-mode: false
    disabled: ["PlayerInteractEvent"]
```
Toggle `whitelist-mode` to disable all events except those in `events.disabled`

`"!EventName"` negation should work, but it hasn't been tested properly :P

Compiling
---------

See [COMPILING.md](COMPILING.md).

Contributing
------------

We happily accept contributions, especially through pull requests on GitHub.

Please read CONTRIBUTING.md for important guidelines to follow.

Submissions must be licensed under the GNU Lesser General Public License v3.