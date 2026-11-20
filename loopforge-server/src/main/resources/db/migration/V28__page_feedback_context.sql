-- The captured page context and element are JSON whose escaping can grow well past their text.
ALTER TABLE page_feedbacks ALTER COLUMN context SET DATA TYPE VARCHAR(20000);
ALTER TABLE page_feedbacks ALTER COLUMN element SET DATA TYPE VARCHAR(20000);
