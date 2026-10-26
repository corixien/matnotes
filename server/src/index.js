const { assertConfig, config } = require('./config');
const { createApp } = require('./app');

assertConfig();
createApp().listen(config.port, () => console.log(`matnotes-sync listening on ${config.port}`));
