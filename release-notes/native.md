Pausing from inside the player reaches the party now.

Fixes
- Watch Party: pausing (or playing) through the provider player's OWN button now tells the room - before, only our overlay's button was relayed, so "one of us paused and it kept playing". Provider play/pause events relay with echo gates (remote events and our own sends never bounce back at the room)

Locked by native QA 91 (Party.providerPlayState + the bridge relay must stay).
