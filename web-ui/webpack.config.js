const path = require("path");
const webpack = require("webpack");

module.exports = (webpackConfigEnv = {}, argv = {}) => {
  const { mode = "development" } = argv;

  const commonConfig = {
    mode,
    context: path.resolve(__dirname),
    module: {
      rules: [
        {
          test: /\.css$|.scss$/,
          use: ["style-loader", "css-loader", "sass-loader"],
        },
        {
          test: /\.[tj]sx?$/,
          use: [{ loader: "ts-loader", options: { configFile: 'tsconfig.webpack.json' } }],
          exclude: /node_modules/,
        },
      ],
    },
    devtool: mode === "production" ? false : "source-map",
    plugins: [],
    resolve: {
      modules: ["node_modules"],
      extensions: [".ts", ".tsx", ".js", ".jsx", ".scss", ".css", ".d.ts"],
    },
  };

  // Connection Browser: UMD for Ignition gateway config page
  const connectionBrowserConfig = {
    ...commonConfig,
    entry: {
      CameraConnectionBrowser: path.join(__dirname, "src/index.ts"),
    },
    output: {
      library: "[name]",
      libraryTarget: "umd",
      umdNamedDefine: true,
      globalObject: 'this',
      filename: "connectionBrowser.js",
      publicPath: "",
      path: path.resolve(__dirname, "build/generated-resources/mounted/"),
    },
    externals: [
      "react",
      "react-dom",
    ],
  };

  // Perspective components: UMD for Perspective runtime (loaded via <script> tag)
  const perspectiveConfig = {
    ...commonConfig,
    entry: {
      perspective: [path.join(__dirname, "src/perspective/index.ts")],
    },
    output: {
      library: {
        name: "CameraDriverComponents",
        type: "umd",
      },
      filename: "[name].js",
      publicPath: "",
      path: path.resolve(__dirname, "build/generated-resources/mounted/"),
    },
    externals: {
      "react": "React",
      "react-dom": "ReactDOM",
      "@inductiveautomation/perspective-client": "PerspectiveClient",
    },
  };

  return [connectionBrowserConfig, perspectiveConfig];
};
