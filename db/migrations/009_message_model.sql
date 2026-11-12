-- 009: which LLM wrote a message (⚙ "Show which AI model replied"). Idempotent, additive.
--
-- * messages.model  the model OpenRouter says answered (after any fallback), e.g. google/gemini-2.5-flash;
--                   null when code wrote the message (templates, events, button answers) and for
--                   messages from before this column existed

alter table messages add column if not exists model text;
