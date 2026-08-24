'use strict';

const path = require('path');

module.exports = {
	mode: 'production',
	target: ['web', 'es2018'],
	entry: path.resolve(__dirname, 'src/classic-entry.js'),
	output: {
		path: path.resolve(__dirname, 'classic-build'),
		filename: 'fr_franckchalon_nextcloud_classic_app.js',
		clean: true,
		globalObject: 'this'
	},
	devtool: false,
	module: {
		rules: [
			{
				test: /\.js$/,
				exclude: /node_modules/,
				use: {
					loader: 'babel-loader',
					options: {
						babelrc: false,
						configFile: false,
						presets: [['@babel/preset-env', { targets: '> 0.5%, Firefox ESR, not dead', modules: false }]],
						plugins: [['@babel/plugin-transform-react-jsx', { pragma: 'createElement' }]]
					}
				}
			},
			{
				test: /\.less$/,
				use: [
					'style-loader',
					{ loader: 'css-loader', options: { modules: { localIdentName: 'nc_[name]_[local]_[hash:base64:5]' }, importLoaders: 1 } },
					'less-loader'
				]
			}
		]
	},
	optimization: {
		minimize: true
	},
	performance: {
		hints: false
	}
};
