# CesiumJS 1.119 (vendored)

The built distribution from https://github.com/CesiumGS/cesium, copyright
2011-2022 Cesium Contributors, under the Apache License 2.0. The full licence
text is in the header of `Cesium.js`.

Bundled rather than fetched from a CDN so the app carries its own copy: the
3D view needs the network for terrain and imagery regardless, but the engine
itself should not be a second thing that can fail to arrive.

`index.js` and `index.cjs` are deliberately not here. They are the ES module
and CommonJS builds of the same library, and a WebView script tag loads
`Cesium.js`; leaving them out saves 7.7MB.

Same version the MavGCS desktop app vendors, so the two views behave alike.
