# Architecture sheets

Fifteen drawings of the system. Open `index.html` in a browser.

Each sheet is data in `sheets/NN-name.mjs`. `build.mjs` turns the data into the pages:

```bash
node docs/architecture/build.mjs          # writes the .html pages
node docs/architecture/build.mjs --svg    # also writes one .svg for each sheet
```

Edit a sheet file, then run the script again. Do not edit the `.html` files by hand.
