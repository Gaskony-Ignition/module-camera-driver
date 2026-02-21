const path = require("path");
const webpack = require("webpack");
const ForkTsCheckerWebpackPlugin = require("fork-ts-checker-webpack-plugin");
const ESLintPlugin = require("eslint-webpack-plugin");

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
          test: /\.[tj]sx?$|\.d\.ts$/,
          use: ["ts-loader", "babel-loader"],
          exclude: /node_modules/,
          parser: { system: false },
        },
      ],
    },
    devtool: "source-map",
    plugins: [
      new ForkTsCheckerWebpackPlugin(),
      new ESLintPlugin({
        files: "./src/**/*.{ts,tsx,js,jsx}",
        failOnError: false,
      }),
    ],
    resolve: {
      modules: ["node_modules"],
      extensions: [".js", ".jsx", ".scss", ".css", ".ts", ".tsx", ".d.ts"],
    },
  };

  // Connection Browser: SystemJS for Ignition gateway config page
  const connectionBrowserConfig = {
    ...commonConfig,
    entry: {
      connectionBrowser: [path.join(__dirname, "src/pages/ConnectionBrowser/index.ts")],
    },
    output: {
      library: { type: "system" },
      filename: "[name].js",
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
