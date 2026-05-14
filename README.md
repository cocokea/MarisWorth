# MarisWorth

MarisWorth is a sell and item worth plugin with multilingual resources and price files.

## What It Handles

- Item worth lookup
- Sell command flow
- Sell history
- Multi-sell flow
- English and Vietnamese messages and GUIs

## Requirements

- Paper / Folia 1.21+
- Java 21

## Installation

1. Put the plugin jar in `plugins`.
2. Start the server once.
3. Configure `config.yml`, `prices.yml`, GUI files, and message files.
4. Restart the server.

## Commands

- `/sell` - Sell the item in hand or current context.
- `/worth [item]` - Check item worth.
- `/sellhistory` - View sell history.
- `/sellmulti` - Use multi-sell flow.

## Files

- `config.yml` - Main settings.
- `prices.yml` - Price definitions.
- `sounds.yml` - Sound configuration.
- `guis/en` and `guis/vi` - GUI files.
- `message_en.yml` and `message_vi.yml` - Message files.

## Notes

- Keep price data consistent with your economy plugin.
- Review GUI and message language pairs together when editing translations.