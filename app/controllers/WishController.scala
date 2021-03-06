package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import models._

object WishController extends Controller with Secured {


    val editWishlistForm = Form(
        tuple(
          "title" -> nonEmptyText(maxLength = 50,minLength = 2 ),
          "description" -> optional(text(maxLength = 2000))
        )
    )

    val searchForm = Form {
        "term" -> optional(text(maxLength = 99))
    }

    val simpleAddWishForm = Form {
        "title" -> text(maxLength = 50,minLength = 2 )
    }

    val editWishForm = Form(
      tuple(
        "title" -> nonEmptyText(maxLength = 50,minLength = 2 ),
        "description" -> optional(text(maxLength = 2000))
      )
    )

    val updateWishlistOrderForm = Form(
      "order" -> text(maxLength=500)
    )

    val addLinkToWishForm = Form(
      "url" -> text(minLength=4,maxLength=100)
    )


    val moveWishForm = Form(
      "targetwishlistid" -> number
    )

    val ValidUrl= """^https?:\/\/[0-9a-zA-Z][^@]+$""".r

    val addOrganiserForm = Form {
      tuple(
        "recipient" -> text(maxLength = 180,minLength = 2 ),
        "wishlistid" -> number,
        "username" -> text(maxLength = 180,minLength = 2 )
      )  verifying("Organiser not found", fields => fields match {
        case (recipient,wishlistid,username) => {
          Recipient.findByUsername(username.trim).isDefined
        }
      }) verifying("Organiser is the recipient", fields => fields match {
        case (recipient,wishlistid,username) => {
          recipient != username
        }
      }) verifying("Recipient already an organiser", fields => fields match {
        case (recipient,wishlistid,username) => {
          Wishlist.findById(wishlistid) match {
            case Some(wishlist) => {
              Recipient.findByUsername(username.trim) match {
                case Some(recipient) =>  !wishlist.isOrganiser(recipient)
                case None => true
              }
            }
            case None => true
          }
        }
      })
    }

    def createWishlist(username:String) = withCurrentRecipient { currentRecipient => implicit request =>
        editWishlistForm.bindFromRequest.fold(
            errors => {
              Logger.warn("Create failed: " + errors)
              BadRequest(views.html.wishlist.createwishlist(errors))
            },
           titleForm => {
              if(username == currentRecipient.username)  {
                Logger.info("New wishlist: " + titleForm._1)

                val wishlist = new Wishlist(None, titleForm._1.trim, None, currentRecipient).save

                Redirect(routes.WishController.showWishlist(username,wishlist.wishlistId.get)).flashing("messageSuccess" -> "Wishlist created")
              } else {
                  Logger.warn("Recipient %s tried to create wishlist for %d".format(currentRecipient.username,username))
                 Unauthorized(views.html.error.permissiondenied())
              }
          }
        )
    }


  def showEditWishlist(username:String,wishlistId:Long) =  isEditorOfWishlist(username,wishlistId) { (wishlist,currentRecipient) => implicit request =>
    val editForm = editWishlistForm.fill((wishlist.title,wishlist.description))
    val organisers = wishlist.findOrganisers
    Ok(views.html.wishlist.editwishlist(wishlist, editForm,organisers,addOrganiserForm))
  }


    def updateWishlist(username:String,wishlistId:Long) = isEditorOfWishlist(username,wishlistId) { (wishlist,currentRecipient) => implicit request =>
      editWishlistForm.bindFromRequest.fold(
        errors => {
          Logger.warn("Update failed: " + errors)
          val organisers = wishlist.findOrganisers
          BadRequest(views.html.wishlist.editwishlist(wishlist,errors,organisers,addOrganiserForm))
        },
        editForm => {
          Logger.info("Updating wishlist: " + editForm)

          wishlist.copy(title=editForm._1,description=editForm._2).update

          Redirect(routes.WishController.showWishlist(username,wishlist.wishlistId.get)).flashing("message" -> "Wishlist updated")
        }
      )
    }



    def deleteWishlist(username:String,wishlistId:Long) = isEditorOfWishlist(username,wishlistId) { (wishlist,currentRecipient) => implicit request =>
        Logger.info("Deleting wishlist: " + wishlistId)

        wishlist.delete

        Redirect(routes.Application.index()).flashing("messageWarning" -> "Wishlist deleted")
    }


    def showWishlist(username:String,wishlistId:Long) =  withWishlist(username,wishlistId) { wishlist => implicit request =>
      val wishes = Wishlist.findWishesForWishlist(wishlist)
      val gravatarUrl = recipientGravatarUrl(wishlist)
      findCurrentRecipient match {
        case Some(recipient) => {
          if( recipient.canEdit(wishlist) ) {
            val editableWishlists = recipient.findEditableWishlists.filter( _ != wishlist )
            Ok(views.html.wishlist.showeditwishlist(wishlist,wishes,simpleAddWishForm,gravatarUrl,editableWishlists))
          } else {
            Ok(views.html.wishlist.showwishlist(wishlist,wishes,simpleAddWishForm,gravatarUrl))
          }
        }
        case None => Ok(views.html.wishlist.showwishlist(wishlist,wishes,simpleAddWishForm,gravatarUrl))
      }
    }


    def showConfirmDeleteWishlist(username:String,wishlistId:Long) = isEditorOfWishlist(username,wishlistId) { (wishlist,currentRecipient) => implicit request =>
        Ok(views.html.wishlist.deletewishlist(wishlist))
    }


    def search = Action { implicit request =>
        searchForm.bindFromRequest.fold(
            errors => {
              Logger.warn("Update failed: " + errors)
              BadRequest
            },
            term => {
                val wishlists = term match {
                    case None => Wishlist.findAll
                    case Some(searchTerm) => Wishlist.searchForWishlistsContaining(searchTerm)
                }
                Ok(views.html.wishlist.listwishlists(wishlists,searchForm.fill(term),editWishlistForm))
            }
        )
   }

   private def recipientGravatarUrl(wishlist:Wishlist) = RecipientController.gravatarUrl(wishlist.recipient)


    def addWishToWishlist(username:String,wishlistId:Long) = isEditorOfWishlist(username,wishlistId) { (wishlist,currentRecipient) => implicit request =>
        simpleAddWishForm.bindFromRequest.fold(
          errors => {
              Logger.warn("Add failed: " + errors)
              val wishes = wishlist.findWishes
              BadRequest(views.html.wishlist.showwishlist(wishlist,wishes,errors,recipientGravatarUrl(wishlist)))
          },
          title => {
              val wish = new Wish(title,None,currentRecipient).save
              wish.addToWishlist(wishlist)
              Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageSuccess" -> "Wish added")
          }
        )
   }


  def removeWishFromWishlist(username:String,wishlistId:Long,wishId:Long) = isEditorOfWish(username,wishlistId,wishId) { (wish,wishlist,currentRecipient) => implicit request =>

      wishlist.removeWish(wish)

      Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageWarning" -> "Wish deleted")
   }



  def updateWish(username:String,wishlistId:Long,wishId:Long) = isEditorOfWish(username,wishlistId,wishId) { (wish,wishlist,currentRecipient) => implicit request =>
      editWishForm.bindFromRequest.fold(
        errors => {
          Logger.warn("Update failed: " + errors)
          val wishes = wishlist.findWishes
           Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageError" -> "Wish update failed")
        },
        editForm => {
          Logger.debug("Update title: " + editForm._1)
          wish.copy(title=editForm._1,description=editForm._2).update
          Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageSuccess" -> "Wish updated")
        }
      )
  }





    def updateWishlistOrder(username:String,wishlistId:Long) = isEditorOfWishlist(username,wishlistId) { (wishlist,currentRecipient) => implicit request =>

      // val wishes = Wishlist.findWishesForWishlist(wishlist)
      updateWishlistOrderForm.bindFromRequest.fold(
        errors => {
          Logger.warn("Update order failed: " + errors)
          Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageError" -> "Order update failed")
        },
        listOrder => {
          Logger.info("Updating wishlist's order: " + wishlistId)

          var ordinalCount = 1;
          listOrder.split(",") map { wishId =>
            WishEntry.findByIds(wishId.toInt,wishlistId) map { wishentry =>
              wishentry.copy(ordinal=Some(ordinalCount)).update
              ordinalCount += 1
            }
          }

          Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("message" -> "Wishlist updated")

        }
      )
    }


  def reserveWish(username:String, wishlistId:Long, wishId:Long) = withWishAndCurrentRecipient(username,wishlistId,wishId) { (wish,wishlist,currentRecipient) => implicit request =>

    wish.reserve(currentRecipient)

    Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageSuccess" -> "Wish reserved")
  }



  def unreserveWish(username:String, wishlistId:Long, wishId:Long) = withWishAndCurrentRecipient(username,wishlistId,wishId) { (wish,wishlist,currentRecipient) => implicit request =>

    wish.unreserve

    Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("message" -> "Wish reservation cancelled")
  }


  def unreserveWishFromProfile(username:String, wishId:Long) = withJustWishAndCurrentRecipient(username,wishId) { (wish,currentRecipient) => implicit request =>

    wish.reservation.map { reservation =>
      if(reservation.isReserver(currentRecipient)){
        wish.unreserve
      }
    }

    Redirect(routes.RecipientController.showProfile(currentRecipient.username)).flashing("message" -> "Wish reservation cancelled")
  }


  def addOrganiserToWishlist(username:String,wishlistId:Long) = isEditorOfWishlist(username,wishlistId) { (wishlist,currentRecipient) => implicit request =>
        addOrganiserForm.bindFromRequest.fold(
          errors => {
              Logger.warn("Add failed: " + errors)
              val editForm = editWishlistForm.fill((wishlist.title,wishlist.description))
              val organisers = wishlist.findOrganisers
              BadRequest(views.html.wishlist.editwishlist(wishlist,editForm,organisers,errors))
          },
          organiserUsername => {
            Recipient.findByUsername(organiserUsername._3) match {
              case Some(organiser) => {
                Logger.info("Adding organiser %s to wishlist %d".format(organiser.username,wishlistId))

                wishlist.addOrganiser(organiser)

                Redirect(routes.WishController.showEditWishlist(username,wishlistId)).flashing("messageSuccess" -> "Organiser added")
              }
              case None => NotFound(views.html.error.notfound())
            }
          }
        )
   }




  def removeOrganiserFromWishlist(username:String,wishlistId:Long,organiserUsername:String) = isEditorOfWishlist(username,wishlistId) { (wishlist,currentRecipient) => implicit request =>
      Logger.info("Removing organiser %s from wishlist %d".format(organiserUsername,wishlistId))
      Recipient.findByUsername(organiserUsername) match {
        case Some(organiser) => {

          wishlist.removeOrganiser(organiser)

          Redirect(routes.WishController.showEditWishlist(username,wishlistId)).flashing("messageRemoved" -> "Organiser removed")
        }
        case None => NotFound(views.html.error.notfound())
      }
   }



  def addLinkToWish(username:String, wishlistId:Long, wishId:Long) =  isEditorOfWish(username,wishlistId,wishId) { (wish,wishlist,currentRecipient) => implicit request =>
    addLinkToWishForm.bindFromRequest.fold(
      errors => {
        Logger.warn("add to link failed")
        Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageError" -> "Link could not be added to wish")
      },
      url => {
        ValidUrl.findFirstIn(url.trim) match {
          case Some(_) => {
            wish.addLink(url)
            Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageSuccess" -> "Link added to wish")
          }
          case None => Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageError" -> "Invalid url. Make sure it starts with http or https?")
        }
      }
    )
  }


  def deleteLinkFromWish(username:String, wishlistId:Long, wishId:Long, linkId:Long) = isEditorOfWish(username,wishlistId,wishId) { (wish,wishlist,currentRecipient) => implicit request =>
    wish.findLink(linkId) match {
      case Some(url) => {
        wish.deleteLink(linkId)
        Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageWarning" -> "Link removed from wish")
      }
      case None => NotFound(views.html.error.notfound())
    }
  }


  def moveWishToWishlist(username:String,wishlistId:Long,wishId:Long) = isEditorOfWish(username,wishlistId,wishId) { (wish,wishlist,currentRecipient) => implicit request =>
    moveWishForm.bindFromRequest.fold(
      errors => {
        Logger.warn("move wish failed")
        Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("messageError" -> "Wish could not be moved")
      },
      targetWishlistId => {
        Wishlist.findById(targetWishlistId) match {
          case Some(targetWishlist) => {
            if( currentRecipient.canEdit(targetWishlist) ) {
              Logger.info("moving wish to %d".format(targetWishlist.wishlistId.get))

              wish.moveToWishlist(targetWishlist)

              Redirect(routes.WishController.showWishlist(username,wishlistId)).flashing("message" -> "Wish moved to other list")
            } else {
              Unauthorized(views.html.error.permissiondenied())
            }
          }
          case None => NotFound(views.html.error.notfound())
        }
      }
    )
  }

}
