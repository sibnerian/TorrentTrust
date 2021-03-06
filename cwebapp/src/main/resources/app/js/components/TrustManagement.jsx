const React = require('React');
const TrustedIdentityButton = require('./includes/TrustedIdentityButton.jsx');
const SubscribeToStateChangesMixin = require('./mixins/SubscribeToStateChangesMixin.js');
const DispatchMixin = require('./mixins').DispatchMixin;
const actions = require('../redux/actions');

module.exports = React.createClass({
  mixins: [SubscribeToStateChangesMixin, DispatchMixin],
  addTrustedKey() {
    const publicKey = this._textarea.value;
    const name = window.prompt('What nickname should we use for this key?');
    this.dispatchAction(actions.addTrustedKey({name, publicKey}));
  },
  onTextAreaKeyUp(e) {
    if (e.which === 13) {
      e.preventDefault();
      this.addTrustedKey();
      this._textarea.value = '';
    }
  },
  render() {
    const {publicKey} = this.state.current_identity;
    const buttons = this.state.trusted_identities[publicKey].map(({name, publicKey}) =>
      <TrustedIdentityButton name={name} publicKey={publicKey} key={publicKey}/>
    );
    return (
      <div className="row trust-management">
        <div className="col-xs-12">
          <h2>Add Trusted Key</h2>
          <textarea
            ref={c => this._textarea = c}
            onKeyPress={this.onTextAreaKeyUp}
            placeholder="Enter a key to trust the associated identity. Other identities belonging to the same user will not be trusted.">
          </textarea>
          <h2>Trusted Identities</h2>
          {buttons.length ? buttons : "Looks like you don't trust anyone."}
        </div>
      </div>
    );
  }
});
