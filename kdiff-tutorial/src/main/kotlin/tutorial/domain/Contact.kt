package tutorial.domain

/**
 * A value object holding another, so the model is three levels deep at one property.
 *
 * A changed phone number is reported at `contact.phone.number`. Routing reads a path one segment at a
 * time, so reaching that property means framing `contact`, then framing `phone` inside it — which is
 * what `under` is for. The alternative, matching on the whole path, is the thing property references
 * exist to avoid.
 */
data class Contact(val email: String?, val phone: Phone)

data class Phone(val country: String, val number: String)
