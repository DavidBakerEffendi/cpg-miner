void process_number(Token *token) /* <=== process_number */ 
{
	int digit;

	position.col = column_number;

	token->type = TOKEN_NUMBER;
	token->value = 0;

	while (isdigit(ch)) {

		digit = ch - '0';
		if (token->value <= ((INT_MAX - digit)/10)) {
			token->value = token->value*10 + digit;
			next_char();
		} else {
			leprintf("number too large");
		}

	}
}

void next_char(void) /* <=== next_char */ 
{
	static char last_read = '\0';

	if ((ch = fgetc(src_file)) != EOF) {
		column_number++;
		if (last_read == '\n') {
			position.line++;
			column_number = 1;
		}
		last_read = ch;
	}
}

void leprintf(const char *fmt, ...) /* <=== leprintf */ 
{
	int istty = isatty(2);
	va_list args;
	const char *pre =
		(istty ? ASCII_BOLD_RED "error:" ASCII_RESET : "error:");

	va_start(args, fmt);
	_weprintf(pre, &position, fmt, args);
	va_end(args);
	exit(2);
}

void isdigit(void p1) {
  /* Unreachable code */
}

