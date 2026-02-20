const path = require("path");
const webpack = require("webpack");
const ForkTsCheckerWebpackPlugin = require("fork-ts-checker-webpack-plugin");
const ESLintPlugin = require("eslint-webpack-plugin");

module.exports = (webpackConfigEnv = {}, argv = {}) => {
  const { mode = "development" } = argv;

  // Do not include these packages in the bundle as they will be provided by the gateway
  const externals = [
    "react",
    "react-dom",
    "@inductiveautomation/perspective-client",
  ];

  return {
    mode,
    entry: {
      connectionBrowser: [path.join(__dirname, "src/pages/ConnectionBrowser/index.ts")],
      perspective: [path.join(__dirname, "src/perspective/index.ts")],
    },
    output: {
      // Export as SystemJS module for Ignition gateway
      library: {
        type: "system",
      },
      filename: "[name].js",
      publicPath: "",
      path: path.resolve(__dirname, "build/generated-resources/mounted/"),
    },
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
    externals,
  };
};
